package com.ta2fga.rotiva.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Grafik ekseni: yuvarlak adımlı işaretler. */
class Ticks(val values: List<Double>, val step: Double, val niceMin: Double, val niceMax: Double)

/** [min, max] aralığı için yaklaşık [target] adet, adımı 1/2/5 x 10^n olan işaretler üretir. */
fun niceTicks(min: Double, max: Double, target: Int = 5): Ticks {
    var lo = min; var hi = max
    if (hi - lo < 1e-9) { lo -= 0.5; hi += 0.5 }
    val raw = (hi - lo) / max(1, target)
    val mag = 10.0.pow(floor(log10(raw)))
    val norm = raw / mag
    val step = mag * when { norm <= 1.0 -> 1.0; norm <= 2.0 -> 2.0; norm <= 5.0 -> 5.0; else -> 10.0 }
    val first = ceil(lo / step - 1e-9) * step
    val vals = ArrayList<Double>()
    var v = first
    while (v <= hi + step * 1e-9) { vals.add(Math.round(v / step * 1e6) / 1e6 * step); v += step }
    return Ticks(vals, step, floor(lo / step + 1e-9) * step, ceil(hi / step - 1e-9) * step)
}

/** Artan sıralı [xs] içinde x'e en yakın dizin (boşsa -1). */
fun nearestIndex(xs: List<Double>, x: Double): Int {
    if (xs.isEmpty()) return -1
    var lo = 0; var hi = xs.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (xs[mid] < x) lo = mid + 1 else hi = mid
    }
    if (lo > 0 && abs(xs[lo - 1] - x) <= abs(xs[lo] - x)) lo--
    return lo
}

/** Rapor izi noktası (grafikler ve harita imleci ortak kullanır). */
class TrackPoint(
    val idx: Int, val t: Double, val distKm: Double, val alt: Double?, val grade: Double?,
    val lat: Double?, val lon: Double?, val speed: Double?, val rpm: Double?, val gear: Int?,
)

fun gradeText(g: Double?): String = if (g == null) "—" else (if (g >= 0) "+" else "") + tr1(g, 1) + "%"

fun altTooltip(p: TrackPoint): String =
    "Yükseklik: ${p.alt?.let { Math.round(it) } ?: "—"} m · Mesafe: ${tr1(p.distKm, 1)} km · Eğim: ${gradeText(p.grade)}"

fun speedTooltip(p: TrackPoint): String = speedTooltip(p, SpeedSeries.values().toSet())

/** Hız grafiğinde açılıp kapatılabilen seriler. Sıra, Y ekseninin hangi seriye ait olacağını belirler (ilk görünen). */
enum class SpeedSeries(val label: String, val unit: String) { SPEED("Hız", "km/s"), RPM("Devir", "dev/dk"), GEAR("Vites", "vites") }

/** Varsayılan: Hız + Vites (devir ayrı ölçekte çizildiği için en çok kalabalık yapan seri o). */
val DEFAULT_SPEED_SERIES: Set<SpeedSeries> = setOf(SpeedSeries.SPEED, SpeedSeries.GEAR)

/** Seçimi değiştirir; son seçili seri kapatılamaz (grafik hiçbir zaman boş kalmaz). */
fun toggleSeries(visible: Set<SpeedSeries>, s: SpeedSeries): Set<SpeedSeries> =
    if (s in visible) (if (visible.size > 1) visible - s else visible) else visible + s

/** rememberSaveable / Prefs için metin biçimi: "SPEED,GEAR". Bozuk ya da boş değer varsayılana döner. */
fun encodeSeries(v: Set<SpeedSeries>): String = SpeedSeries.values().filter { it in v }.joinToString(",") { it.name }

fun decodeSeries(s: String?): Set<SpeedSeries> {
    val v = s.orEmpty().split(",").mapNotNull { n -> SpeedSeries.values().firstOrNull { it.name == n.trim() } }.toSet()
    return v.ifEmpty { DEFAULT_SPEED_SERIES }
}

/** Serinin ölçek tavanı (alt sınır her zaman 0); boş seriler için makul taban değerler. */
fun seriesTop(s: SpeedSeries, pts: List<TrackPoint>): Double = when (s) {
    SpeedSeries.SPEED -> max(60.0, pts.mapNotNull { it.speed }.maxOrNull() ?: 0.0)
    SpeedSeries.RPM -> max(4000.0, pts.mapNotNull { it.rpm }.maxOrNull() ?: 0.0)
    SpeedSeries.GEAR -> max(6.0, (pts.mapNotNull { it.gear }.maxOrNull() ?: 0) + 1.0)   // 4..7 vites
}

/** Serinin işaretleri: vitesler tam sayı adımlı, diğerleri ~4 aralık. */
fun seriesTicks(s: SpeedSeries, pts: List<TrackPoint>): Ticks {
    val top = seriesTop(s, pts)
    return niceTicks(0.0, top, if (s == SpeedSeries.GEAR) top.toInt() else 4)
}

/** Sol Y ekseni: görünen ilk serinin (Hız > Devir > Vites) ölçeği, birimi ve işaretleri. */
class SpeedAxis(val series: SpeedSeries, val ticks: Ticks, val unitLabel: String)

fun speedAxis(visible: Set<SpeedSeries>, pts: List<TrackPoint>): SpeedAxis {
    val s = SpeedSeries.values().firstOrNull { it in visible } ?: SpeedSeries.SPEED
    return SpeedAxis(s, seriesTicks(s, pts), "dk  /  ${s.unit}")
}

fun seriesValue(s: SpeedSeries, p: TrackPoint): Double? = when (s) {
    SpeedSeries.SPEED -> p.speed
    SpeedSeries.RPM -> p.rpm
    SpeedSeries.GEAR -> p.gear?.toDouble()
}

/** İpucu: yalnız görünen serilerin değerleri + süre. */
fun speedTooltip(p: TrackPoint, visible: Set<SpeedSeries>): String {
    val parts = ArrayList<String>()
    if (SpeedSeries.SPEED in visible) parts += "Hız: ${p.speed?.let { Math.round(it) } ?: "—"} km/s"
    if (SpeedSeries.RPM in visible) parts += "Devir: ${p.rpm?.let { Math.round(it) } ?: "—"}"
    if (SpeedSeries.GEAR in visible) parts += "Vites: ${p.gear ?: "—"}"
    parts += fmtDuration(p.t)
    return parts.joinToString(" · ")
}
