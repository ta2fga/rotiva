package com.ta2fga.rotiva.core

/** ELM327 katmanı: taşıma arayüzü, ISO-TP ayrıştırma, OBD yardımcıları (desktop app/elm327.py). */
open class ElmError(msg: String) : Exception(msg)
class ElmTimeout(msg: String) : ElmError(msg)
class NoData(msg: String = "Veri yok") : ElmError(msg)
class NegativeResponse(val service: Int, val nrc: Int) :
    ElmError("Negatif yanıt: servis %02X, kod 0x%02X".format(service, nrc))

/** Bloklayan taşıma. [read] veri yoksa boş dizi döner; bağlantı koptuysa ElmError fırlatır. */
interface Transport {
    fun open()
    fun close()
    fun write(data: ByteArray)
    fun read(timeoutMs: Long): ByteArray
}

val ELM_ERRORS = listOf("UNABLE TO CONNECT", "BUS BUSY", "CAN ERROR", "BUFFER FULL", "BUS ERROR", "DATA ERROR",
    "FB ERROR", "LV RESET", "ACT ALERT")
private val HEX = Regex("^[0-9A-F]+$")

fun cleanLines(raw: String, cmd: String = ""): List<String> {
    val out = ArrayList<String>()
    for (l in raw.replace(">", "").split(Regex("[\\r\\n]+"))) {
        val ln = l.trim()
        if (ln.isEmpty() || ln.equals(cmd.trim(), ignoreCase = true)) continue
        out.add(ln)
    }
    return out
}

/** Protokol numarasına göre başlık hane sayısı (ATH1, boşluksuz). */
fun headerLenFor(proto: String): Int {
    val p = proto.uppercase().trimStart('A').take(1)
    return when (p) { "6" -> 3; "7" -> 8; "8" -> 3; "9" -> 8; else -> 6 }
}

private fun hexBytes(s: String): IntArray = IntArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) }

/** OBD satırlarını ISO-TP birleştirerek [(başlık, bayt dizisi)] döndürür (PCI baytı atılmış). */
fun parseFrames(lines: List<String>, headerLen: Int = 3): List<Pair<String, IntArray>> {
    val msgs = LinkedHashMap<String, ArrayList<Int>>()
    val totals = HashMap<String, Int?>()
    for (ln in lines) {
        val s = ln.replace(" ", "").uppercase()
        if (!HEX.matches(s) || s.length < headerLen + 2) continue
        if (headerLen == 6) {
            val hdr = s.substring(0, 6)
            val body = s.substring(6, s.length - 2)
            if (body.length % 2 != 0) continue
            msgs.getOrPut(hdr) { ArrayList() }.addAll(hexBytes(body).toList())
            continue
        }
        val hdr = s.substring(0, headerLen)
        val body = s.substring(headerLen)
        if (body.length % 2 != 0) continue
        val b = hexBytes(body)
        if (b.isEmpty()) continue
        val st = msgs.getOrPut(hdr) { ArrayList() }
        when (b[0] shr 4) {
            0 -> { st.clear(); st.addAll(b.drop(1).take(b[0] and 0xF)); totals[hdr] = null }
            1 -> { st.clear(); st.addAll(b.drop(2)); totals[hdr] = ((b[0] and 0xF) shl 8) or (b.getOrElse(1) { 0 }) }
            2 -> st.addAll(b.drop(1))
        }
    }
    return msgs.map { (h, v) ->
        val total = totals[h]
        val data = if (total != null && total > 0) v.take(total) else v
        h to data.toIntArray()
    }
}

fun decodeDtc(b1: Int, b2: Int): String =
    "PCBU"[b1 shr 6].toString() + ((b1 shr 4) and 3) + "%X".format(b1 and 0xF) + "%02X".format(b2)

fun decodeDtcList(data: IntArray, hasCount: Boolean): List<String> {
    val d = if (hasCount && data.isNotEmpty()) data.copyOfRange(1, data.size) else data
    val codes = ArrayList<String>()
    var i = 0
    while (i + 1 < d.size) {
        if (!(d[i] == 0 && d[i + 1] == 0)) codes.add(decodeDtc(d[i], d[i + 1]))
        i += 2
    }
    return codes
}

fun raiseIfError(lines: List<String>) {
    val joined = lines.joinToString(" | ").uppercase()
    if ("NO DATA" in joined) throw NoData()
    for (e in ELM_ERRORS) if (e in joined) throw ElmError(e)
    if (lines.any { it.trim() == "?" }) throw ElmError("Komut anlaşılmadı (?)")
}

/** Mod/PID yanıtından veri (mod+PID'den sonrası). */
fun extractService(lines: List<String>, mode: Int, pid: Int?, headerLen: Int): IntArray {
    raiseIfError(lines)
    for ((_, data) in parseFrames(lines, headerLen)) {
        if (data.isNotEmpty() && data[0] == 0x40 + mode) {
            if (pid == null) return data.copyOfRange(1, data.size)
            if (data.size > 1 && data[1] == pid) return data.copyOfRange(2, data.size)
        }
        if (data.size >= 3 && data[0] == 0x7F && data[1] == mode) throw NegativeResponse(mode, data[2])
    }
    throw NoData("Beklenen yanıt yok")
}

class ElmConnectInfo(val version: String, val protocol: String, val ecuOk: Boolean, val voltage: Double?)

/** Bloklayan ELM327 istemcisi. */
class Elm327(val transport: Transport, private val log: (String) -> Unit = {}) {
    @Volatile var connected = false
        private set
    var version = ""
    var protocol = ""
    var protocolNum = ""
    var headerLen = 3
    var supported: Set<Int> = emptySet()
    var defaultTimeoutMs = 3000L
    private val lock = Any()

    val isCan get() = protocolNum.take(1) in listOf("6", "7", "8", "9")

    fun exchange(cmd: String, timeoutMs: Long = defaultTimeoutMs): List<String> = synchronized(lock) {
        log("> $cmd")
        while (transport.read(0).isNotEmpty()) { /* eski yanıt artıklarını at */ }
        transport.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        var done = false
        while (System.currentTimeMillis() < end) {
            val chunk = transport.read(50)
            if (chunk.isNotEmpty()) {
                sb.append(String(chunk, Charsets.US_ASCII))
                if (sb.contains('>')) { done = true; break }
            }
        }
        if (!done) throw ElmTimeout("Zaman aşımı: $cmd")
        val lines = cleanLines(sb.toString(), cmd)
        lines.forEach { log("< $it") }
        lines
    }

    fun connect(protocol: String = "0"): ElmConnectInfo {
        transport.open()
        try {
            val r = exchange("ATZ", 6000)
            version = r.firstOrNull { "ELM" in it.uppercase() || "OBD" in it.uppercase() } ?: "ELM327"
            for (c in listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATAT1")) exchange(c)
            exchange("ATSP$protocol")
            connected = true
            var ecuOk = true
            try {
                supported = scanSupported()
            } catch (e: NoData) {
                ecuOk = false
            } catch (e: ElmError) {
                log("! ${e.message}"); ecuOk = false
            }
            readProtocol()
            return ElmConnectInfo(version, this.protocol, ecuOk, voltage())
        } catch (e: Exception) {
            disconnect(); throw e
        }
    }

    private fun readProtocol() {
        try {
            val n = exchange("ATDPN")
            val pn = (n.firstOrNull() ?: "").trim().uppercase()
            protocolNum = if (pn.length == 2 && pn[0] == 'A') pn.substring(1) else pn
            val d = exchange("ATDP")
            protocol = (d.firstOrNull() ?: "").replace("AUTO, ", "").trim()
        } catch (_: ElmError) {
        }
        headerLen = headerLenFor(protocolNum.ifEmpty { "6" })
    }

    private fun service(mode: Int, pid: Int?, lines: List<String>? = null, timeoutMs: Long = defaultTimeoutMs): IntArray {
        val l = lines ?: exchange("%02X".format(mode) + (if (pid != null) "%02X".format(pid) else ""), timeoutMs)
        return extractService(l, mode, pid, headerLen)
    }

    private fun scanSupported(): Set<Int> {
        val masks = LinkedHashMap<Int, IntArray>()
        val lines = exchange("0100", 12000)
        raiseIfError(lines)
        readProtocol()
        var base = 0
        while (true) {
            val data = try {
                service(0x01, base, if (base == 0) lines else null)
            } catch (e: NegativeResponse) {
                break
            } catch (e: NoData) {
                if (base == 0) throw e
                break
            }
            val m = IntArray(4) { data.getOrElse(it) { 0 } }
            masks[base] = m
            if (m[3] and 1 == 0) break
            base += 0x20
            if (base > 0xC0) break
        }
        return supportedFromMasks(masks)
    }

    fun disconnect() {
        connected = false
        try { transport.close() } catch (_: Exception) {}
    }

    fun voltage(): Double? = try {
        exchange("ATRV").firstOrNull()?.replace(Regex("[^0-9.]"), "")?.toDouble()
    } catch (_: Exception) { null }

    fun readPid(pid: Pid): Double? = try {
        val data = service(0x01, pid.pid)
        if (data.size < pid.nbytes) null else pid.decode(data)
    } catch (_: NoData) { null }

    /** mode: 0x03 kayıtlı, 0x07 bekleyen, 0x0A kalıcı. */
    fun readDtcs(mode: Int): List<String> {
        val lines = try {
            exchange("%02X".format(mode), 6000).also { raiseIfError(it) }
        } catch (_: NoData) { return emptyList() }
        val codes = LinkedHashSet<String>()
        for ((_, data) in parseFrames(lines, headerLen)) {
            if (data.isNotEmpty() && data[0] == 0x40 + mode) codes += decodeDtcList(data.copyOfRange(1, data.size), isCan)
        }
        return codes.toList()
    }

    fun clearDtcs(): Boolean = exchange("04", 6000).any { "44" in it.replace(" ", "") }
}
