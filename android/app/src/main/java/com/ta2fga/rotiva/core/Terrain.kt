package com.ta2fga.rotiva.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Birleşik, zaman damgalı örnek (OBD + GPS + barometre + ivmeölçer). t = epoch saniye. */
data class Sample(
    val t: Double,
    var rpm: Double? = null,
    var speed: Double? = null,          // km/s (OBD varsa OBD, yoksa GPS)
    var load: Double? = null,
    var coolant: Double? = null,
    var map: Double? = null,
    var maf: Double? = null,
    var frp: Double? = null,
    var gear: Int? = null,
    var lat: Double? = null,
    var lon: Double? = null,
    var gpsSpeed: Double? = null,       // km/s
    var bearing: Double? = null,
    var alt: Double? = null,            // birleşik (GPS+baro) yükseklik, m
    var acc: Double? = null,            // yatay konum doğruluğu, m
    var baroAlt: Double? = null,
    var gLong: Double? = null,          // boylamasına ivme, g (+ hızlanma)
    var gLat: Double? = null,           // yanal ivme, g (+ sağa); örnek aralığının medyanı (darbelere dayanıklı)
    var gVert: Double? = null,          // dikey darbe, g: örnek aralığında yerçekimi eksenindeki en büyük |ivme|
    // analiz sırasında türetilir:
    var grade: Double? = null,          // eğim %
    var dist: Double = 0.0,             // kümülatif mesafe, m
)

/** Toplam çıkış/iniş: histerezisli (varsayılan 2,5 m). */
class ElevationTracker(private val threshold: Double = 2.5) {
    var ascent = 0.0; private set
    var descent = 0.0; private set
    var maxAlt: Double? = null; private set
    var minAlt: Double? = null; private set
    private var ref: Double? = null
    private var dir = 0   // 1 yukarı, -1 aşağı, 0 belirsiz

    fun update(alt: Double) {
        maxAlt = maxAlt?.let { max(it, alt) } ?: alt
        minAlt = minAlt?.let { min(it, alt) } ?: alt
        val r = ref
        if (r == null) { ref = alt; return }
        when (dir) {
            0 -> {
                if (alt - r >= threshold) { ascent += alt - r; ref = alt; dir = 1 }
                else if (r - alt >= threshold) { descent += r - alt; ref = alt; dir = -1 }
            }
            1 -> {
                if (alt > r) { ascent += alt - r; ref = alt }
                else if (r - alt >= threshold) { descent += r - alt; ref = alt; dir = -1 }
            }
            else -> {
                if (alt < r) { descent += r - alt; ref = alt }
                else if (alt - r >= threshold) { ascent += alt - r; ref = alt; dir = 1 }
            }
        }
    }
}

fun ascentDescent(alts: List<Double>, threshold: Double = 2.5): Pair<Double, Double> {
    val t = ElevationTracker(threshold)
    alts.forEach { t.update(it) }
    return t.ascent to t.descent
}

/** Barometre basıncından (hPa) standart atmosfere göre yükseklik (m). */
fun pressureToAltitude(hPa: Double, p0: Double = 1013.25): Double = 44330.0 * (1.0 - (hPa / p0).pow(1.0 / 5.255))

/**
 * GPS + barometre birleştirme (1-B Kalman): barometre göreli değişimi (kontrol girdisi), GPS mutlak yüksekliği
 * sabitler. Barometre yoksa yalnızca GPS ölçümüyle düzleştirir.
 */
class AltitudeFuser {
    var alt: Double? = null; private set
    private var p = 100.0
    private var lastBaro: Double? = null
    private var lastT = 0.0
    var hasBaro = false; private set
    var baroAlt: Double? = null; private set

    /** Barometre okuması (hPa); GPS'ten bağımsız, yüksek hızda gelir. */
    fun onBaro(t: Double, hPa: Double) {
        val b = pressureToAltitude(hPa)
        baroAlt = b
        hasBaro = true
        val lb = lastBaro
        val a = alt
        if (lb != null && a != null) {
            val dt = max(0.0, t - lastT)
            alt = a + (b - lb)
            p += 0.02 * dt           // barometre sürüklenmesi
        }
        lastBaro = b
        lastT = t
    }

    /** GPS yüksekliği (m) ve dikey/yatay doğruluk (m, bilinmiyorsa null). */
    fun onGps(t: Double, gpsAlt: Double, accuracy: Double?) {
        val a = alt
        if (a == null) { alt = gpsAlt; p = 25.0; if (!hasBaro) lastT = t; return }
        if (!hasBaro) {
            val dt = max(0.0, t - lastT)
            p += 1.0 * dt            // GPS-yalnız: süreç gürültüsü
            lastT = t
        }
        val sigma = max(3.0, accuracy ?: 8.0)
        val r = sigma * sigma
        val k = p / (p + r)
        alt = a + k * (gpsAlt - a)
        p *= (1 - k)
    }
}

/** Her örnek için eğim (%): ≥ [minWindow] m mesafe penceresinde d(yükseklik)/d(mesafe), merkezli. Sınır ±30 %. */
fun annotateTerrain(S: List<Sample>, minWindow: Double = 50.0) {
    if (S.isEmpty()) return
    // kümülatif mesafe (m): hız integrali
    S[0].dist = 0.0
    for (i in 1 until S.size) {
        val dt = (S[i].t - S[i - 1].t).coerceIn(0.0, 3.0)
        val v = ((S[i].speed ?: 0.0) + (S[i - 1].speed ?: 0.0)) / 2 / 3.6
        S[i].dist = S[i - 1].dist + v * dt
    }
    val idx = S.indices.filter { S[it].alt != null }
    if (idx.size < 2) { S.forEach { it.grade = null }; return }
    val half = minWindow / 2
    var lo = 0; var hi = 0
    for (n in idx.indices) {
        val i = idx[n]
        // geriye: dist[i] - dist[j] >= half olan en büyük j
        while (lo + 1 < n && S[i].dist - S[idx[lo + 1]].dist >= half) lo++
        if (hi < n) hi = n
        while (hi < idx.size - 1 && S[idx[hi]].dist - S[i].dist < half) hi++
        var a = idx[lo]; var b = idx[hi]
        val back = S[i].dist - S[a].dist >= half
        val fwd = S[b].dist - S[i].dist >= half
        if (!back && fwd) { a = i } else if (back && !fwd) { b = i }
        else if (!back && !fwd) { S[i].grade = null; continue }
        val dd = S[b].dist - S[a].dist
        if (dd < minWindow * 0.98) { S[i].grade = null; continue }
        S[i].grade = ((S[b].alt!! - S[a].alt!!) / dd * 100).coerceIn(-30.0, 30.0)
    }
    // Başlangıç/bitişteki pencere dışı örnekleri en yakın bilinen eğimle doldur
    var last: Double? = null
    for (i in S.indices) { val g = S[i].grade; if (g != null) last = g else if (last != null && S[i].alt != null) S[i].grade = last }
    var next: Double? = null
    for (i in S.indices.reversed()) { val g = S[i].grade; if (g != null) next = g else if (next != null && S[i].alt != null) S[i].grade = next }
}

// ---------------------------------------------------------------------------- yönelimden bağımsız ivme
/**
 * Telefon yönelimine bağlı olmayan boylamasına/yanal ivme.
 *  - Yerçekimi vektörü ile yatay düzlem bulunur.
 *  - İleri eksen, GPS/OBD hız türevi ile yatay ivmenin korelasyonundan (düz giderken hızlanma/frenleme) öğrenilir.
 *  - Sağ eksen = aşağı × ileri.
 */
class VehicleFrame {
    // İleri eksen toplamı (cihaz koordinatı, normalize edilmemiş); birim vektör kullanımda hesaplanır
    private var ax = 0.0; private var ay = 0.0; private var az = 0.0
    private var weight = 0.0
    var calibrated = false; private set

    private var sx = 0.0; private var sy = 0.0; private var sz = 0.0; private var n = 0
    private var dx = 0.0; private var dy = 0.0; private var dz = 1.0
    private var lastV: Double? = null
    private var lastT = 0.0
    private var lastBearing: Double? = null

    private var tickLongSum = 0.0; private var tickN = 0; private var tickLatPeak = 0.0
    private var tickLat = DoubleArray(64); private var tickLatN = 0
    private var tickVertPeak = 0.0; private var tickVertSq = 0.0; private var tickVertN = 0
    private var tickJerkPeak = 0.0; private var lastDot: Double? = null
    var lastLong: Double? = null; private set
    var lastLat: Double? = null; private set
    var lastVert: Double? = null; private set

    /**
     * Bir örnekleme aralığının özeti (g).
     *  - [long]: boyuna ortalama; [lat]: yanal medyan (kısa darbe sızıntısını bastırır); [latPeak]: ham yanal tepe.
     *  - [vert]: yerçekimi eksenindeki en büyük |doğrusal ivme| (çukur/kasis darbesi), [vertRms]: dikey RMS,
     *    [vertJerk]: ardışık ölçümler arası en büyük dikey değişim (g/ölçüm). Dikey değerler kalibrasyon gerektirmez.
     * Kalibre değilse long/lat/latPeak null; ivme verisi hiç gelmediyse dikey değerler de null.
     */
    data class Tick(
        val long: Double?, val lat: Double?, val latPeak: Double? = null,
        val vert: Double? = null, val vertRms: Double? = null, val vertJerk: Double? = null,
    )

    private fun fwd(): DoubleArray {
        val m = sqrt(ax * ax + ay * ay + az * az)
        return if (m < 1e-9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(ax / m, ay / m, az / m)
    }

    /** a: doğrusal ivme (m/s²), g: yerçekimi vektörü (cihaz koordinatında, aşağı yönünde; |g|≈9.81). */
    fun onAccel(lx: Double, ly: Double, lz: Double, gx: Double, gy: Double, gz: Double) {
        val gn = sqrt(gx * gx + gy * gy + gz * gz)
        if (gn < 1.0) return
        dx = gx / gn; dy = gy / gn; dz = gz / gn
        val dot = lx * dx + ly * dy + lz * dz
        val hx = lx - dot * dx; val hy = ly - dot * dy; val hz = lz - dot * dz
        sx += hx; sy += hy; sz += hz; n++
        // dikey (yerçekimi ekseni) darbe: tepe, RMS ve sarsıntı (jerk)
        val vg = dot / G
        if (abs(vg) > tickVertPeak) tickVertPeak = abs(vg)
        tickVertSq += vg * vg; tickVertN++
        lastDot?.let { val j = abs(vg - it); if (j > tickJerkPeak) tickJerkPeak = j }
        lastDot = vg
        if (calibrated) {
            val (l, t) = project(hx, hy, hz)
            tickLongSum += l; tickN++
            if (abs(t) > abs(tickLatPeak)) tickLatPeak = t
            if (tickLatN == tickLat.size) {
                if (tickLat.size < MAX_TICK_SAMPLES) tickLat = tickLat.copyOf(tickLat.size * 2)
                else { System.arraycopy(tickLat, 1, tickLat, 0, tickLat.size - 1); tickLatN-- }   // en eskiyi at
            }
            tickLat[tickLatN++] = t
        }
    }

    /** Yatay ivme vektörünü (m/s²) (boyuna g, yanal g) olarak döndürür. Yanal + = sağa. */
    fun project(hx: Double, hy: Double, hz: Double): Pair<Double, Double> {
        val f = fwd()
        val long = hx * f[0] + hy * f[1] + hz * f[2]
        val rx = dy * f[2] - dz * f[1]; val ry = dz * f[0] - dx * f[2]; val rz = dx * f[1] - dy * f[0]
        val lat = hx * rx + hy * ry + hz * rz
        return long / G to lat / G
    }

    /** Hız (m/s) ve yön (derece) ölçümü (~1 Hz). Düz giderken hızlanma/frenleme ile ileri ekseni kalibre eder. */
    fun onSpeed(t: Double, vMs: Double, bearingDeg: Double?) {
        val lv = lastV
        if (lv != null && t - lastT in 0.4..2.5 && n > 5) {
            val dt = t - lastT
            val dv = (vMs - lv) / dt
            var dB = 0.0
            val lb = lastBearing
            if (bearingDeg != null && lb != null) dB = abs(((bearingDeg - lb + 540) % 360) - 180) / dt
            val straight = bearingDeg == null || lb == null || dB < 3.0
            if (straight && vMs > 1.5 && abs(dv) > 0.7) {
                val mx = sx / n; val my = sy / n; val mz = sz / n
                val mag = sqrt(mx * mx + my * my + mz * mz)
                if (mag > 0.3) {
                    // hızlanırken yatay ivme ileri yönde; frenlerken geri yönde -> işaretle çevir
                    val sgn = if (dv > 0) 1.0 else -1.0
                    val w = min(abs(dv), 3.0)
                    ax += sgn * mx / mag * w; ay += sgn * my / mag * w; az += sgn * mz / mag * w
                    weight += w
                    if (weight >= 4.0) calibrated = true
                }
            }
        }
        sx = 0.0; sy = 0.0; sz = 0.0; n = 0
        lastV = vMs; lastT = t; lastBearing = bearingDeg
    }

    /**
     * Son örnekleme aralığının özeti ([Tick]); sayaçları sıfırlar. `val (long, lat) = takeTick()` biçimi eskisi gibi
     * çalışır; yanal değer artık ham tepe değil medyandır (çukur darbesi tek başına viraj sayılmasın diye).
     */
    fun takeTick(): Tick {
        val vert = if (tickVertN > 0) tickVertPeak else null
        val vertRms = if (tickVertN > 0) sqrt(tickVertSq / tickVertN) else null
        val jerk = if (tickVertN > 1) tickJerkPeak else null
        val out = if (!calibrated || tickN == 0) Tick(null, null, null, vert, vertRms, jerk)
        else {
            val l = tickLongSum / tickN
            val med = median(tickLat, tickLatN)
            lastLong = l; lastLat = med
            Tick(l, med, tickLatPeak, vert, vertRms, jerk)
        }
        lastVert = vert
        tickLongSum = 0.0; tickN = 0; tickLatPeak = 0.0; tickLatN = 0
        tickVertPeak = 0.0; tickVertSq = 0.0; tickVertN = 0; tickJerkPeak = 0.0
        return out
    }

    companion object {
        const val G = 9.80665
        private const val MAX_TICK_SAMPLES = 1024
        private fun median(a: DoubleArray, n: Int): Double? {
            if (n == 0) return null
            val c = a.copyOf(n); c.sort()
            return if (n % 2 == 1) c[n / 2] else (c[n / 2 - 1] + c[n / 2]) / 2
        }
    }
}

/** Merkezkaç tahmini: yanal ivme ≈ hız × yönelme hızı (kalibrasyon yokken yedek). g cinsinden, + sağa. */
fun lateralFromBearing(vMs: Double, dBearingDegPerS: Double): Double =
    vMs * Math.toRadians(dBearingDegPerS) / VehicleFrame.G

/** İki enlem/boylam arası mesafe (m). */
fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
    val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * r * Math.asin(min(1.0, sqrt(a)))
}
