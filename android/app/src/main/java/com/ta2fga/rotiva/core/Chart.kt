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

fun speedTooltip(p: TrackPoint): String =
    "Hız: ${p.speed?.let { Math.round(it) } ?: "—"} km/s · Devir: ${p.rpm?.let { Math.round(it) } ?: "—"} · Vites: ${p.gear ?: "—"} · ${fmtDuration(p.t)}"
