package com.ta2fga.obd2.core

/** Standart Mod 01 PID tanımları ve birleşik değer kataloğu (desktop app/pids.py). */
val CATEGORIES = listOf("Motor", "Yakıt", "Hava / Turbo", "Sıcaklık", "DPF / Emisyon", "Elektrik", "Araç", "Diğer")

class Pid(
    val pid: Int, val key: String, val name: String, val unit: String, val nbytes: Int,
    val vmin: Double, val vmax: Double, val category: String,
    val warnHi: Double? = null, val critHi: Double? = null,
    val warnLo: Double? = null, val critLo: Double? = null,
    val decimals: Int = 0,
    val decode: (IntArray) -> Double,
)

private fun u8(d: IntArray) = d[0].toDouble()
private fun u16(d: IntArray) = (d[0] * 256 + d[1]).toDouble()

val PIDS: List<Pid> = listOf(
    Pid(0x04, "load", "Motor Yükü", "%", 1, 0.0, 100.0, "Motor", 85.0, 95.0) { u8(it) * 100 / 255 },
    Pid(0x05, "coolant", "Soğutma Suyu", "°C", 1, -40.0, 130.0, "Sıcaklık", 105.0, 112.0, 60.0, 0.0) { u8(it) - 40 },
    Pid(0x0B, "map", "Manifold Basıncı (MAP)", "kPa", 1, 0.0, 255.0, "Hava / Turbo", 230.0, 250.0) { u8(it) },
    Pid(0x0C, "rpm", "Motor Devri", "d/dk", 2, 0.0, 5000.0, "Motor", 4200.0, 4800.0) { u16(it) / 4 },
    Pid(0x0D, "speed", "Araç Hızı", "km/s", 1, 0.0, 200.0, "Araç") { u8(it) },
    Pid(0x0F, "iat", "Emme Havası", "°C", 1, -40.0, 100.0, "Sıcaklık", 60.0, 80.0) { u8(it) - 40 },
    Pid(0x10, "maf", "Hava Debisi (MAF)", "g/s", 2, 0.0, 100.0, "Hava / Turbo", decimals = 1) { u16(it) / 100 },
    Pid(0x11, "throttle", "Gaz Kelebeği / Pedal", "%", 1, 0.0, 100.0, "Motor") { u8(it) * 100 / 255 },
    Pid(0x1F, "runtime", "Motor Çalışma Süresi", "sn", 2, 0.0, 65535.0, "Motor") { u16(it) },
    Pid(0x21, "dist_mil", "Arıza Lambası ile Mesafe", "km", 2, 0.0, 65535.0, "Araç") { u16(it) },
    // Ham değer 10 kPa birimidir; bar olarak gösterilir (masaüstündeki formülle aynı: ham/10).
    Pid(0x23, "frp", "Yakıt Ray Basıncı", "bar", 2, 0.0, 2500.0, "Yakıt", 1900.0, 2100.0) { u16(it) / 10.0 },
    Pid(0x2F, "fuel_level", "Yakıt Seviyesi", "%", 1, 0.0, 100.0, "Yakıt", null, null, 15.0, 8.0) { u8(it) * 100 / 255 },
    Pid(0x33, "baro", "Barometrik Basınç", "kPa", 1, 0.0, 255.0, "Hava / Turbo") { u8(it) },
    Pid(0x42, "vmod", "Akü / Modül Voltajı", "V", 2, 0.0, 16.0, "Elektrik", 15.0, 15.8, 12.0, 11.4, 2) { u16(it) / 1000 },
    Pid(0x46, "ambient", "Dış Sıcaklık", "°C", 1, -40.0, 60.0, "Sıcaklık") { u8(it) - 40 },
    Pid(0x5C, "oil_temp", "Yağ Sıcaklığı", "°C", 1, -40.0, 150.0, "Sıcaklık", 120.0, 135.0, 50.0, 0.0) { u8(it) - 40 },
)

val BY_PID: Map<Int, Pid> = PIDS.associateBy { it.pid }
val BY_KEY: Map<String, Pid> = PIDS.associateBy { it.key }
const val BATTERY_KEY = "atrv"

/** masks: {0x00: 4 bayt, 0x20: ...} -> desteklenen PID kümesi. */
fun supportedFromMasks(masks: Map<Int, IntArray>): Set<Int> {
    val out = HashSet<Int>()
    for ((base, data) in masks) for (i in 0 until 32) {
        if (data[i / 8] and (0x80 shr (i % 8)) != 0) out.add(base + i + 1)
    }
    return out
}

class Item(
    val key: String, val name: String, val unit: String, val category: String,
    val vmin: Double, val vmax: Double,
    val warnHi: Double? = null, val critHi: Double? = null, val warnLo: Double? = null, val critLo: Double? = null,
    val decimals: Int = 1, val pid: Pid? = null,
)

enum class Status { OK, WARN, CRIT, NONE }

fun statusOf(item: Item, v: Double?): Status {
    if (v == null) return Status.NONE
    if ((item.critHi != null && v >= item.critHi) || (item.critLo != null && v <= item.critLo)) return Status.CRIT
    if ((item.warnHi != null && v >= item.warnHi) || (item.warnLo != null && v <= item.warnLo)) return Status.WARN
    return Status.OK
}

fun buildCatalog(supported: Set<Int>? = null): List<Item> {
    val items = ArrayList<Item>()
    for (p in PIDS) {
        if (supported != null && p.pid !in supported) continue
        items.add(Item(p.key, p.name, p.unit, p.category, p.vmin, p.vmax, p.warnHi, p.critHi, p.warnLo, p.critLo,
            p.decimals, p))
    }
    items.add(Item(BATTERY_KEY, "Akü Voltajı (adaptör)", "V", "Elektrik", 8.0, 16.0, 15.0, 15.8, 12.0, 11.4, 2))
    return items
}
