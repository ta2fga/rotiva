package com.ta2fga.obd2.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Demo sürüş döngüsü (desktop app/simulator.py DriveCycle): şehir + otoyol, vites geçişleri, birkaç sert olay. */
class DriveCycle {
    class Seg(val end: Double, val speed: Double, val gear: Int, val load: Double? = null, val wob: Double = 0.0)
    data class Point(val speed: Double, val rpm: Double, val gear: Int?, val load: Double, val coolant: Double)

    companion object {
        val TRUE_RATIOS = doubleArrayOf(7.8, 14.6, 21.4, 29.8, 38.6)
        const val IDLE = 800.0
        const val SHIFT_S = 0.7
        const val LENGTH = 131.0
        val SEGS = listOf(
            Seg(4.0, 0.0, 0), Seg(10.0, 26.0, 1), Seg(15.0, 34.0, 2), Seg(21.0, 46.0, 3), Seg(27.0, 52.0, 4),
            Seg(45.0, 52.0, 4), Seg(49.0, 25.0, 4), Seg(53.0, 0.0, 0), Seg(58.0, 0.0, 0),
            Seg(60.0, 20.0, 1), Seg(62.0, 40.0, 2), Seg(65.0, 58.0, 3),
            Seg(70.0, 75.0, 4), Seg(80.0, 106.0, 4),
            Seg(84.0, 106.0, 5), Seg(92.0, 112.0, 5, null, 9.0), Seg(100.0, 112.0, 5, null, 9.0),
            Seg(104.0, 60.0, 5),
            Seg(110.0, 46.0, 5, 82.0), Seg(116.0, 58.0, 5, 82.0),
            Seg(126.0, 0.0, 0), Seg(131.0, 0.0, 0),
        )
    }

    fun at(t: Double): Point {
        val tc = t % LENGTH
        var prevT = 0.0; var prevV = 0.0; var prevG = 0
        var seg: Seg? = null
        for (sg in SEGS) {
            if (tc <= sg.end) { seg = sg; break }
            prevT = sg.end; prevV = sg.speed; prevG = sg.gear
        }
        val sg = seg ?: SEGS.last()
        val f = (tc - prevT) / max(sg.end - prevT, 1e-6)
        var v = prevV + (sg.speed - prevV) * f
        val acc = (sg.speed - prevV) / max(sg.end - prevT, 1e-6) / 3.6
        if (sg.wob != 0.0) v += sg.wob * sin(tc * 0.9)
        v = max(0.0, v)
        val g = sg.gear
        val shifting = g != prevG && prevG >= 1 && g >= 1 && (tc - prevT) < SHIFT_S
        var gear: Int? = g
        var rpm: Double
        if (g >= 1) {
            rpm = max(IDLE, v / TRUE_RATIOS[g - 1] * 1000)
            if (shifting) {
                gear = null
                val old = max(IDLE, v / TRUE_RATIOS[prevG - 1] * 1000)
                rpm = max(IDLE, old * (1 - 0.35 * (tc - prevT) / SHIFT_S))
            }
        } else { rpm = IDLE; gear = null }
        rpm += 10 * sin(t * 4.1) + 6 * sin(t * 9.7)
        var load = sg.load ?: max(5.0, min(95.0, 18 + 14 * acc + 0.0006 * v * v))
        if (v < 1) load = 20 + 2 * sin(t / 3)
        val coolant = min(88.0, 38 + 1.2 * t)
        return Point(v, rpm, gear, load, coolant)
    }
}

/** ELM327 + ECU simülatörü: komutlara gerçek bir adaptör gibi yanıt verir. */
class SimulatedELM(private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
    private val cycle = DriveCycle()
    private val t0 = clock()
    private var spaces = true
    private val dtcs = mapOf(0x03 to listOf(0x0087, 0x2002), 0x07 to listOf(0x0299), 0x0A to emptyList<Int>())
    private var cleared = false

    private fun u8(v: Double) = byteArrayOf(max(0, min(255, v.toInt())).toByte())
    private fun u16(v: Double): ByteArray { val x = max(0, min(65535, v.toInt())); return byteArrayOf((x shr 8).toByte(), (x and 0xFF).toByte()) }

    private fun pidValue(pid: Int, t: Double): ByteArray? {
        val p = cycle.at(t)
        return when (pid) {
            0x04 -> u8(p.load * 255 / 100)
            0x05 -> u8(p.coolant + 40)
            0x0B -> u8(100 + p.load * 1.2)
            0x0C -> u16(p.rpm * 4)
            0x0D -> u8(p.speed)
            0x0F -> u8(32 + 40 + 3 * sin(t / 9))
            0x10 -> u16(p.rpm / 40 * 100 * (0.6 + p.load / 100))
            0x11 -> u8(p.load * 0.8 * 255 / 100)
            0x1F -> u16(t)
            0x21 -> u16(0.0)
            0x23 -> u16((22000 + p.load * 100) / 10)
            0x2F -> u8(62.0 * 255 / 100)
            0x33 -> u8(101.0)
            0x42 -> u16((13.9 + 0.1 * sin(t / 5)) * 1000)
            0x46 -> u8(18.0 + 40)
            0x5C -> u8(min(95.0, 25 + t * 0.6) + 40)
            else -> null
        }
    }

    private val masks = mapOf(0x00 to "983BA011", 0x20 to "B0002001")

    private fun frames(payload: ByteArray): List<String> {
        val sep = if (spaces) " " else ""
        val hdr = "7E8"
        val parts = ArrayList<ByteArray>()
        if (payload.size <= 7) parts.add(byteArrayOf(payload.size.toByte()) + payload)
        else {
            parts.add(byteArrayOf((0x10 or (payload.size shr 8)).toByte(), (payload.size and 0xFF).toByte()) + payload.copyOfRange(0, 6))
            var rest = payload.copyOfRange(6, payload.size); var n = 1
            while (rest.isNotEmpty()) {
                val take = rest.copyOfRange(0, min(7, rest.size))
                parts.add(byteArrayOf((0x20 or (n and 0xF)).toByte()) + take)
                rest = rest.copyOfRange(take.size, rest.size); n++
            }
        }
        return parts.map { hdr + sep + it.joinToString(sep) { b -> "%02X".format(b.toInt() and 0xFF) } }
    }

    fun handle(line: String): String {
        val c = line.trim().uppercase().replace(" ", "")
        if (c.isEmpty()) return "\r"
        val out = dispatch(c)
        return (if (out.isEmpty()) "" else out.joinToString("\r") + "\r") + "\r"
    }

    private fun dispatch(c: String): List<String> {
        if (c.startsWith("AT")) return at(c.substring(2))
        if (!Regex("[0-9A-F]+").matches(c) || c.length % 2 != 0) return listOf("?")
        val d = IntArray(c.length / 2) { c.substring(it * 2, it * 2 + 2).toInt(16) }
        val t = clock() - t0
        val mode = d[0]
        if (mode == 0x01 && d.size >= 2) {
            val pid = d[1]
            if (pid % 0x20 == 0) {
                val m = masks[pid] ?: return listOf("NO DATA")
                return frames(byteArrayOf(0x41, pid.toByte()) + m.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }
            val v = pidValue(pid, t) ?: return listOf("NO DATA")
            return frames(byteArrayOf(0x41, pid.toByte()) + v)
        }
        if (mode == 0x03 || mode == 0x07 || mode == 0x0A) {
            val codes = if (cleared) emptyList() else dtcs.getValue(mode)
            val body = byteArrayOf(codes.size.toByte()) + codes.flatMap { listOf((it shr 8).toByte(), (it and 0xFF).toByte()) }.toByteArray()
            return frames(byteArrayOf((mode + 0x40).toByte()) + body)
        }
        if (mode == 0x04) { cleared = true; return frames(byteArrayOf(0x44)) }
        return listOf("NO DATA")
    }

    private fun at(a: String): List<String> = when {
        a.startsWith("Z") -> listOf("", "ELM327 v2.1")
        a == "I" -> listOf("ELM327 v2.1")
        a.startsWith("S") && !a.startsWith("SP") && !a.startsWith("ST") && !a.startsWith("SH") -> { spaces = a.substring(1) != "0"; listOf("OK") }
        a == "DPN" -> listOf("A6")
        a == "DP" -> listOf("AUTO, ISO 15765-4 (CAN 11/500)")
        a == "RV" -> listOf("13.8V")
        else -> listOf("OK")
    }
}

/** Bellek içi taşıma: seri port gibi davranır. */
class SimTransport(private val latencyMs: Long = 15, clock: () -> Double = { System.currentTimeMillis() / 1000.0 }) : Transport {
    private val sim = SimulatedELM(clock)
    private var buf = StringBuilder()
    private var isOpen = false
    override fun open() { isOpen = true }
    override fun close() { isOpen = false }
    override fun write(data: ByteArray) {
        if (!isOpen) throw ElmError("Bağlantı kapalı")
        if (latencyMs > 0) Thread.sleep(latencyMs)
        for (line in String(data, Charsets.US_ASCII).split("\r")) {
            if (line.isNotBlank()) synchronized(this) { buf.append(sim.handle(line)).append('>') }
        }
    }
    override fun read(timeoutMs: Long): ByteArray {
        if (!isOpen) throw ElmError("Bağlantı kapalı")
        synchronized(this) {
            if (buf.isEmpty()) {
                if (timeoutMs > 0) { try { Thread.sleep(min(timeoutMs, 10)) } catch (_: InterruptedException) {} }
                if (buf.isEmpty()) return ByteArray(0)
            }
            val b = buf.toString().toByteArray(Charsets.US_ASCII)
            buf = StringBuilder()
            return b
        }
    }
}

/**
 * Demo modu için yapay GPS/ivme: hız profilinden mesafe integrali alınır; rota bir daire üzerinde,
 * yükseklik tepeli bir profildir (eğim yaklaşık ±%10'a kadar).
 */
class DemoWorld {
    var dist = 0.0; private set
    private var lastT: Double? = null
    private var lastSpeed = 0.0
    var lat = 41.0; var lon = 29.0; var alt = 100.0; var bearing = 0.0
    var gLong = 0.0; var gLat = 0.0

    fun altitudeAt(d: Double) = 120 + 45 * sin(d / 420.0) + 12 * sin(d / 97.0)

    fun step(t: Double, speedKmh: Double) {
        val lt = lastT
        val v = speedKmh / 3.6
        if (lt != null) {
            val dt = t - lt
            dist += v * dt
            gLong = ((speedKmh - lastSpeed) / 3.6 / max(dt, 1e-3)) / VehicleFrame.G
        }
        lastT = t; lastSpeed = speedKmh
        val radius = 1500.0
        val ang = dist / radius
        lat = 41.0 + (radius * sin(ang)) / 111320.0
        lon = 29.0 + (radius * (1 - cos(ang))) / (111320.0 * cos(Math.toRadians(41.0)))
        bearing = (Math.toDegrees(ang) + 90) % 360
        alt = altitudeAt(dist)
        gLat = (v * v / radius) / VehicleFrame.G
    }
}
