package com.ta2fga.rotiva.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Sürüş puanlama (desktop app/scoring.py) + eğim bağlamı + ivmeölçer çapraz doğrulama + viraj. */
data class ScoreCfg(
    val harshAccelMs2: Double = 2.5,
    val harshBrakeMs2: Double = -3.0,
    val accelWindowS: Double = 1.0,
    val overRevRpm: Double = 3500.0,
    val lugRpm: Double = 1300.0, val lugLoad: Double = 70.0, val lugMinGear: Int = 3, val lugMinS: Double = 1.0,
    val idealShiftLo: Double = 2000.0, val idealShiftHi: Double = 2500.0,
    val lateShiftRpm: Double = 2800.0, val earlyShiftRpm: Double = 1500.0,
    val coldCoolant: Double = 60.0, val coldRpm: Double = 2800.0, val coldMinS: Double = 0.5,
    val longIdleS: Double = 120.0, val idleSpeed: Double = 1.0, val idleMinRpm: Double = 300.0,
    val highwayKmh: Double = 80.0, val unstableWindowS: Double = 20.0, val unstableRangeKmh: Double = 15.0,
    val unstableCooldownS: Double = 30.0,
    val cooldownS: Double = 3.0, val maxDt: Double = 3.0,
    val minKm: Double = 2.0, val ecoAccelOk: Double = 0.6,
    // eğim bağlamı
    val steepUphill: Double = 6.0, val midUphill: Double = 3.0,       // sert hızlanma cezası azalır
    val lugUphillGrade: Double = 4.0, val lugUphillRpm: Double = 1500.0, val lugUphillMinGear: Int = 4,
    val descentGrade: Double = -3.0, val descentMinM: Double = 500.0, val descentBrakeShare: Double = 0.25,
    val descentBrakeMs2: Double = -0.6,
    val cornerG: Double = 0.35,
    // viraj: sürekli olmalı (2 Hz'de en az 2 ardışık örnek, ≈1 sn kapsama), dikey darbeyle çakışmamalı,
    // GPS yönü varsa olay çevresinde (±cornerHeadingWinS) en az cornerHeadingDeg derece dönüş olmalı
    val cornerMinS: Double = 0.8, val cornerMinSamples: Int = 2, val cornerVertMaskG: Double = 0.4,
    // maske yalnızca yalıtılmış darbeye: çevredeki (±cornerVertLocalS) dikey medyanın en az bu katı olmalı
    // (sürekli bozuk/parke yolda her örnek "darbe" sayılıp gerçek virajlar maskelenmesin)
    val cornerVertLocalS: Double = 5.0, val cornerVertLocalK: Double = 1.5,
    val cornerHeadingDeg: Double = 8.0, val cornerHeadingWinS: Double = 1.5,
    // çukur / bozuk yol: kısa dikey darbe (yol durumu; sürücü puanını varsayılan olarak etkilemez)
    val potholeG: Double = 0.6, val potholeMinKmh: Double = 10.0, val potholeMergeS: Double = 1.5,
    val penPothole: Double = 0.0,
    val penHarshAccel: Double = 9.0, val penHarshBrake: Double = 10.0, val penOverRev: Double = 6.0,
    val penLugging: Double = 5.0, val penLateUp: Double = 3.0, val penEarlyUp: Double = 1.5,
    val penUnstable: Double = 8.0, val penLugUphill: Double = 8.0, val penDescentBrake: Double = 15.0,
    val penCorner: Double = 8.0,
    val penCold: Double = 25.0, val penLongIdle: Double = 20.0, val idleShareOk: Double = 0.25,
    val shiftDevDiv: Double = 30.0,
)

val DEFAULT_CFG = ScoreCfg()

val SUBS = listOf("Hızlanma", "Frenleme", "Vites kullanımı / devir", "Rölanti", "Soğuk motor",
    "Hız istikrarı / Eko", "Eğim yönetimi", "Viraj")
val WEIGHTS = mapOf("Hızlanma" to 0.18, "Frenleme" to 0.18, "Vites kullanımı / devir" to 0.22, "Rölanti" to 0.08,
    "Soğuk motor" to 0.08, "Hız istikrarı / Eko" to 0.13, "Eğim yönetimi" to 0.08, "Viraj" to 0.05)

val LABELS = mapOf(
    "harsh_accel" to "Sert hızlanma", "harsh_brake" to "Sert frenleme", "over_rev" to "Yüksek devir",
    "lugging" to "Düşük devirde zorlanma", "late_upshift" to "Geç vites büyütme",
    "early_upshift" to "Erken vites büyütme", "cold_abuse" to "Soğuk motora yüklenme",
    "long_idle" to "Uzun rölanti", "unstable_speed" to "Dengesiz hız",
    "lug_uphill" to "Yokuşta düşük devir", "descent_brake" to "İnişte sürekli fren",
    "harsh_corner" to "Sert viraj", "pothole" to "Çukur / bozuk yol",
)

data class Event(
    val type: String, val label: String, val t: Double, val ts: Double, val value: Double, val unit: String,
    val text: String, val dur: Double = 0.0, val weight: Double = 1.0,
    val lenKm: Double = 0.0, val avgGrade: Double = 0.0,
)

class Metrics(
    var durationS: Double = 0.0, var distanceKm: Double = 0.0, var avgSpeed: Double = 0.0, var maxSpeed: Double = 0.0,
    var idleS: Double = 0.0, var idleShare: Double = 0.0, var shiftCount: Int = 0, var upshiftCount: Int = 0,
    var avgShiftRpm: Int? = null, var meanAbsAccel: Double = 0.0, var maxRpm: Double = 0.0,
    var gearTime: Map<Int, Double> = emptyMap(), var counts: Map<String, Int> = emptyMap(),
    // arazi
    var hasAltitude: Boolean = false, var ascent: Double = 0.0, var descent: Double = 0.0,
    var maxAlt: Double? = null, var minAlt: Double? = null,
    var maxUpGrade: Double? = null, var maxDownGrade: Double? = null,
    var pctUp: Double = 0.0, var pctFlat: Double = 0.0, var pctDown: Double = 0.0,
    var longestDescentKm: Double = 0.0, var longestDescentGrade: Double = 0.0,
    var maxLatG: Double = 0.0, var maxLongG: Double = 0.0, var maxVertG: Double = 0.0,
)

class ScoreResult(
    val total: Int?, val subs: Map<String, Int>, val events: List<Event>, val metrics: Metrics, val short: Boolean,
)

fun tr1(x: Double, d: Int = 1): String = String.format(Locale.forLanguageTag("tr-TR"), "%.${d}f", x)

private fun dts(S: List<Sample>, cfg: ScoreCfg): DoubleArray {
    val out = DoubleArray(S.size)
    for (i in 1 until S.size) out[i] = min(max(S[i].t - S[i - 1].t, 0.0), cfg.maxDt)
    return out
}

fun distanceKm(S: List<Sample>, cfg: ScoreCfg = DEFAULT_CFG): Double {
    val d = dts(S, cfg)
    var sum = 0.0
    for (i in 1 until S.size) sum += ((S[i].speed ?: 0.0) + (S[i - 1].speed ?: 0.0)) / 2 * d[i] / 3600
    return sum
}

/** Her örnek için ivme (m/s²): >= accelWindowS geriye bakan fark. */
fun accelerations(S: List<Sample>, cfg: ScoreCfg = DEFAULT_CFG): Array<Double?> {
    val acc = arrayOfNulls<Double>(S.size)
    var j = 0
    for (i in S.indices) {
        val si = S[i].speed ?: continue
        while (j + 1 < i && S[i].t - S[j + 1].t >= cfg.accelWindowS) j++
        val sj = S[j].speed
        if (j < i && sj != null) {
            val dt = S[i].t - S[j].t
            if (dt >= cfg.accelWindowS * 0.8 && dt <= cfg.maxDt) acc[i] = (si - sj) / 3.6 / dt
        }
    }
    return acc
}

class Episode(val a: Int, val b: Int)

/** Ardışık bayrakları (cooldown içinde birleştirerek) olaylara çevirir. */
fun episodes(S: List<Sample>, flags: BooleanArray, minDur: Double = 0.0, cooldown: Double = 3.0): List<Episode> {
    val runs = ArrayList<IntArray>()
    var i = 0
    val n = S.size
    while (i < n) {
        if (flags[i]) {
            var k = i
            while (k + 1 < n && flags[k + 1]) k++
            runs.add(intArrayOf(i, k)); i = k + 1
        } else i++
    }
    val merged = ArrayList<IntArray>()
    for (r in runs) {
        if (merged.isNotEmpty() && S[r[0]].t - S[merged.last()[1]].t <= cooldown) merged.last()[1] = r[1]
        else merged.add(r)
    }
    return merged.filter { S[it[1]].t - S[it[0]].t >= minDur }.map { Episode(it[0], it[1]) }
}

/**
 * Yalnızca sürekli bayrak dizilerini bırakır: en az [minSamples] ardışık örnek ve [minS] sn kapsama
 * (son - ilk zaman + tipik örnek aralığı). Tek örneklik sıçramalar (ör. çukur darbesi) elenir.
 */
fun sustained(S: List<Sample>, flags: BooleanArray, minS: Double, minSamples: Int): BooleanArray {
    val out = BooleanArray(flags.size)
    val gaps = (1 until S.size).map { S[it].t - S[it - 1].t }.filter { it > 0 }.sorted()
    val step = if (gaps.isEmpty()) 0.5 else gaps[gaps.size / 2]
    var i = 0
    while (i < flags.size) {
        if (!flags[i]) { i++; continue }
        var k = i
        while (k + 1 < flags.size && flags[k + 1]) k++
        if (k - i + 1 >= minSamples && S[k].t - S[i].t + step >= minS - 1e-9) for (x in i..k) out[x] = true
        i = k + 1
    }
    return out
}

/** Her örnek için ±[winS] sn içindeki [f] değerlerinin medyanı (değer yoksa 0). */
fun localMedian(S: List<Sample>, f: (Sample) -> Double?, winS: Double): DoubleArray {
    val out = DoubleArray(S.size)
    var lo = 0; var hi = 0
    for (i in S.indices) {
        while (S[i].t - S[lo].t > winS) lo++
        while (hi + 1 < S.size && S[hi + 1].t - S[i].t <= winS) hi++
        val w = (lo..hi).mapNotNull { f(S[it]) }.sorted()
        out[i] = if (w.isEmpty()) 0.0 else if (w.size % 2 == 1) w[w.size / 2] else (w[w.size / 2 - 1] + w[w.size / 2]) / 2
    }
    return out
}

/** [a]..[b] olayı çevresinde (±[winS] sn) GPS yönündeki toplam dönüş (derece, mutlak). Yön verisi yoksa null. */
fun headingChange(S: List<Sample>, a: Int, b: Int, winS: Double, minKmh: Double = 5.0): Double? {
    val t0 = S[a].t - winS; val t1 = S[b].t + winS
    var lo = a; while (lo > 0 && S[lo - 1].t >= t0) lo--
    var hi = b; while (hi < S.size - 1 && S[hi + 1].t <= t1) hi++
    var prev: Double? = null; var sum = 0.0; var cnt = 0
    for (x in lo..hi) {
        val br = S[x].bearing ?: continue
        if ((S[x].speed ?: 0.0) < minKmh) continue
        prev?.let { sum += ((br - it + 540) % 360) - 180 }
        prev = br; cnt++
    }
    return if (cnt >= 2) abs(sum) else null
}

private fun clamp(v: Double) = max(0.0, min(100.0, v))

fun analyze(input: List<Sample>, cfg: ScoreCfg = DEFAULT_CFG, t0In: Double? = null): ScoreResult {
    val S = input.filter { it.speed != null || it.rpm != null }
    val metrics = Metrics()
    if (S.size < 3) return ScoreResult(null, emptyMap(), emptyList(), metrics, true)
    val t0 = t0In ?: S[0].t
    val dt = dts(S, cfg)
    val dur = dt.sum()
    val km = distanceKm(S, cfg)
    annotateTerrain(S)
    val n = S.size
    val ev = ArrayList<Event>()
    fun add(type: String, idx: Int, value: Double, unit: String, text: String, durS: Double = 0.0,
            weight: Double = 1.0, lenKm: Double = 0.0, avgGrade: Double = 0.0) {
        ev.add(Event(type, LABELS.getValue(type), Math.round((S[idx].t - t0) * 10) / 10.0, S[idx].t,
            Math.round(value * 10) / 10.0, unit, text, Math.round(durS * 10) / 10.0, weight, lenKm, avgGrade))
    }
    fun gradeAt(i: Int) = S[i].grade ?: 0.0

    val acc = accelerations(S, cfg)
    val gl = DoubleArray(n) { i -> S[i].gLong?.let { it * VehicleFrame.G } ?: Double.NaN }
    val hasAccel = S.any { it.gLong != null }
    val hasLat = S.any { it.gLat != null }

    // --- sert hızlanma (eğim eşiği + ivmeölçer doğrulaması)
    fun accThr(i: Int) = cfg.harshAccelMs2 + 0.03 * max(0.0, gradeAt(i))
    val aFlags = BooleanArray(n) { i ->
        val a = acc[i]; val g = gl[i]
        (a != null && a > accThr(i)) || (!g.isNaN() && g > accThr(i) && (S[i].speed ?: 0.0) > 3)
    }
    for (e in episodes(S, aFlags, 0.0, cfg.cooldownS)) {
        var pk = 0.0; var accelHit = false
        for (x in e.a..e.b) {
            val v = max(acc[x] ?: 0.0, if (gl[x].isNaN()) 0.0 else gl[x])
            pk = max(pk, v)
            if (!gl[x].isNaN() && gl[x] > accThr(x)) accelHit = true
        }
        var w = 1.0
        if (hasAccel && !accelHit) w *= 0.5            // yalnızca hız türevi: ivmeölçer doğrulamadı
        val g0 = (e.a..e.b).maxOf { gradeAt(it) }
        w *= if (g0 >= cfg.steepUphill) 0.5 else if (g0 >= cfg.midUphill) 0.75 else 1.0
        val tag = (if (accelHit) " (ivmeölçerle doğrulandı)" else "") + (if (g0 >= cfg.midUphill) ", %${tr1(g0, 0)} yokuş" else "")
        add("harsh_accel", e.a, pk, "m/s²", "${tr1(pk)} m/s² ivmelenme$tag", weight = w)
    }

    // --- sert frenleme
    val bFlags = BooleanArray(n) { i ->
        val a = acc[i]; val g = gl[i]
        (a != null && a < cfg.harshBrakeMs2) || (!g.isNaN() && g < cfg.harshBrakeMs2 && (S[i].speed ?: 0.0) > 3)
    }
    for (e in episodes(S, bFlags, 0.0, cfg.cooldownS)) {
        var pk = 0.0; var accelHit = false
        for (x in e.a..e.b) {
            pk = min(pk, min(acc[x] ?: 0.0, if (gl[x].isNaN()) 0.0 else gl[x]))
            if (!gl[x].isNaN() && gl[x] < cfg.harshBrakeMs2) accelHit = true
        }
        var w = 1.0
        if (hasAccel && !accelHit) w *= 0.5
        val gMin = (e.a..e.b).minOf { gradeAt(it) }
        val tag = (if (accelHit) " (ivmeölçerle doğrulandı)" else "") +
            (if (gMin <= cfg.descentGrade) ", iniş: motor freni önerilir" else "")
        add("harsh_brake", e.a, pk, "m/s²", "${tr1(pk)} m/s² yavaşlama$tag", weight = w)
    }

    // --- yüksek devir
    val rp = DoubleArray(n) { S[it].rpm ?: Double.NaN }
    for (e in episodes(S, BooleanArray(n) { (S[it].rpm ?: 0.0) > cfg.overRevRpm }, 0.0, cfg.cooldownS)) {
        val m = (e.a..e.b).maxOf { rp[it] }
        add("over_rev", e.a, m, "d/dk", "${m.roundToInt()} d/dk")
    }

    // --- yokuşta düşük devir (öncelikli) ve genel zorlanma
    val upFlags = BooleanArray(n) { i ->
        val s = S[i]
        s.gear != null && s.gear!! >= cfg.lugUphillMinGear && s.rpm != null && s.rpm!! < cfg.lugUphillRpm &&
            s.load != null && s.load!! > cfg.lugLoad && gradeAt(i) > cfg.lugUphillGrade
    }
    for (e in episodes(S, upFlags, cfg.lugMinS, cfg.cooldownS)) {
        val m = (e.a..e.b).minOf { if (rp[it].isNaN()) 1e9 else rp[it] }
        val g = (e.a..e.b).maxOf { gradeAt(it) }
        add("lug_uphill", e.a, m, "d/dk",
            "${S[e.a].gear}. viteste %${tr1(g, 0)} yokuşta ${m.roundToInt()} d/dk, yük >%${cfg.lugLoad.roundToInt()}",
            S[e.b].t - S[e.a].t, avgGrade = g)
    }
    val lugFlags = BooleanArray(n) { i ->
        val s = S[i]
        !upFlags[i] && s.gear != null && s.gear!! >= cfg.lugMinGear && s.rpm != null && s.rpm!! < cfg.lugRpm &&
            s.load != null && s.load!! > cfg.lugLoad
    }
    for (e in episodes(S, lugFlags, cfg.lugMinS, cfg.cooldownS)) {
        val m = (e.a..e.b).minOf { if (rp[it].isNaN()) 1e9 else rp[it] }
        add("lugging", e.a, m, "d/dk", "${S[e.a].gear}. viteste ${m.roundToInt()} d/dk, yük >%${cfg.lugLoad.roundToInt()}",
            S[e.b].t - S[e.a].t)
    }

    // --- soğuk motor
    val coldFlags = BooleanArray(n) { i ->
        S[i].coolant != null && S[i].coolant!! < cfg.coldCoolant && S[i].rpm != null && S[i].rpm!! > cfg.coldRpm
    }
    for (e in episodes(S, coldFlags, cfg.coldMinS, 5.0)) {
        val m = (e.a..e.b).maxOf { if (rp[it].isNaN()) 0.0 else rp[it] }
        add("cold_abuse", e.a, m, "d/dk", "Su ${tr1(S[e.a].coolant!!, 0)} °C iken ${m.roundToInt()} d/dk")
    }

    // --- vites değişimleri
    val shifts = detectShifts(S.map { Triple(it.t, it.rpm, it.gear) })
    val ups = shifts.filter { it.up }
    val tIndex = HashMap<Double, Int>()
    S.forEachIndexed { i, s -> tIndex[s.t] = i }
    for (x in ups) {
        val i = tIndex[x.t] ?: 0
        if (x.rpm > cfg.lateShiftRpm) add("late_upshift", i, x.rpm, "d/dk", "${x.fromGear}→${x.toGear} geçişi ${x.rpm.roundToInt()} d/dk")
        else if (x.rpm < cfg.earlyShiftRpm) add("early_upshift", i, x.rpm, "d/dk", "${x.fromGear}→${x.toGear} geçişi ${x.rpm.roundToInt()} d/dk")
    }
    val avgShift = if (ups.isNotEmpty()) ups.map { it.rpm }.average() else null

    // --- rölanti
    val idle = BooleanArray(n) { i ->
        S[i].speed != null && S[i].speed!! <= cfg.idleSpeed && S[i].rpm != null && S[i].rpm!! >= cfg.idleMinRpm
    }
    var idleS = 0.0
    for (i in 0 until n) if (idle[i]) idleS += dt[i]
    var idleRun = 0.0; var fired = false
    for (i in 0 until n) {
        if (idle[i]) {
            idleRun += dt[i]
            if (idleRun >= cfg.longIdleS && !fired) { fired = true; add("long_idle", i, idleRun, "sn", "${tr1(idleRun / 60)} dk rölanti", idleRun) }
        } else { idleRun = 0.0; fired = false }
    }

    // --- dengesiz hız (otoyol)
    run {
        val w = cfg.unstableWindowS
        var lastT = -1e9; var lo = 0
        for (i in 0 until n) {
            val s = S[i]
            if (s.speed == null) continue
            while (S[lo].t < s.t - w) lo++
            val seg = (lo..i).mapNotNull { S[it].speed }
            if (seg.size > 5 && s.t - S[lo].t >= w * 0.9 && seg.min() >= cfg.highwayKmh - 5 &&
                seg.max() - seg.min() > cfg.unstableRangeKmh && s.t - lastT > cfg.unstableCooldownS
            ) {
                add("unstable_speed", i, seg.max() - seg.min(), "km/s",
                    "${w.roundToInt()} sn içinde ${(seg.max() - seg.min()).roundToInt()} km/s oynama")
                lastT = s.t
            }
        }
    }

    // --- viraj (yanal ivme): sürekli, dikey darbesiz ve (GPS yönü varsa) gerçekten dönülen yerlerde
    if (hasLat) {
        // dikey darbenin baskın olduğu örnek: yanal değer büyük olasılıkla darbe sızıntısıdır
        val vLocal = localMedian(S, { it.gVert }, cfg.cornerVertLocalS)
        val jolt = BooleanArray(n) { i ->
            val v = S[i].gVert ?: 0.0
            v >= cfg.cornerVertMaskG && v >= abs(S[i].gLat ?: 0.0) && v >= cfg.cornerVertLocalK * vLocal[i]
        }
        val raw = BooleanArray(n) { i ->
            S[i].gLat != null && abs(S[i].gLat!!) > cfg.cornerG && (S[i].speed ?: 0.0) > 10 && !jolt[i]
        }
        val cFlags = sustained(S, raw, cfg.cornerMinS, cfg.cornerMinSamples)
        for (e in episodes(S, cFlags, 0.0, cfg.cooldownS)) {
            val turn = headingChange(S, e.a, e.b, cfg.cornerHeadingWinS)
            if (turn != null && turn < cfg.cornerHeadingDeg) continue      // düz yolda yanal sıçrama: viraj değil
            val pk = (e.a..e.b).maxOf { abs(S[it].gLat ?: 0.0) }
            val tag = if (turn != null) ", ${turn.roundToInt()}° dönüş" else ""
            add("harsh_corner", e.a, pk, "g", "${tr1(pk, 2)} g yanal ivme, ${(S[e.a].speed ?: 0.0).roundToInt()} km/s$tag",
                S[e.b].t - S[e.a].t)
        }
    }

    // --- çukur / bozuk yol (dikey darbe); yakın darbeler potholeMergeS içinde birleşir
    if (S.any { it.gVert != null }) {
        val pFlags = BooleanArray(n) { i -> (S[i].gVert ?: 0.0) > cfg.potholeG && (S[i].speed ?: 0.0) > cfg.potholeMinKmh }
        for (e in episodes(S, pFlags, 0.0, cfg.potholeMergeS)) {
            val i = (e.a..e.b).maxBy { S[it].gVert ?: 0.0 }
            val pk = S[i].gVert ?: 0.0
            val d = S[e.b].t - S[e.a].t
            val what = if (d >= 3.0) "bozuk yol, ${d.roundToInt()} sn" else "dikey darbe"
            add("pothole", i, pk, "g", "${tr1(pk, 2)} g $what, ${(S[i].speed ?: 0.0).roundToInt()} km/s", d)
        }
    }

    // --- uzun inişlerde sürekli fren
    val descents = ArrayList<Triple<Int, Int, Double>>()   // (a, b, uzunluk m)
    for (e in episodes(S, BooleanArray(n) { (S[it].grade ?: 0.0) < cfg.descentGrade }, 0.0, 10.0)) {
        val len = S[e.b].dist - S[e.a].dist
        if (len >= cfg.descentMinM) descents.add(Triple(e.a, e.b, len))
    }
    for ((a, b, len) in descents) {
        var tot = 0; var br = 0; var gs = 0.0
        for (x in a..b) {
            if ((S[x].speed ?: 0.0) < 10) continue
            tot++
            gs += gradeAt(x)
            val v = if (!gl[x].isNaN()) gl[x] else acc[x]
            if (v != null && v < cfg.descentBrakeMs2) br++
        }
        val avgG = if (tot > 0) gs / tot else 0.0
        if (len / 1000 > metrics.longestDescentKm) { metrics.longestDescentKm = len / 1000; metrics.longestDescentGrade = avgG }
        if (tot > 5 && br.toDouble() / tot >= cfg.descentBrakeShare) {
            add("descent_brake", a, br.toDouble() / tot * 100, "%",
                "${tr1(len / 1000)} km iniş, ortalama ${tr1(avgG, 0)}%, sürenin %${(br * 100 / tot)}'sinde fren",
                S[b].t - S[a].t, lenKm = len / 1000, avgGrade = avgG)
        }
    }

    // --- alt puanlar
    val per10 = max(km, cfg.minKm) / 10.0
    val cnt = HashMap<String, Double>()
    LABELS.keys.forEach { cnt[it] = 0.0 }
    for (e in ev) cnt[e.type] = cnt.getValue(e.type) + e.weight
    fun c(k: String) = cnt.getValue(k)
    val subs = LinkedHashMap<String, Double>()
    subs["Hızlanma"] = clamp(100 - cfg.penHarshAccel * c("harsh_accel") / per10)
    subs["Frenleme"] = clamp(100 - cfg.penHarshBrake * c("harsh_brake") / per10)
    val dev = if (ups.size >= 2 && avgShift != null) max(0.0, max(avgShift - cfg.idealShiftHi, cfg.idealShiftLo - avgShift)) else 0.0
    val gPen = (cfg.penOverRev * c("over_rev") + cfg.penLugging * c("lugging") + cfg.penLateUp * c("late_upshift") +
        cfg.penEarlyUp * c("early_upshift")) / per10
    subs["Vites kullanımı / devir"] = clamp(100 - gPen - dev / cfg.shiftDevDiv)
    val idleShare = if (dur > 0) idleS / dur else 0.0
    subs["Rölanti"] = clamp(100 - cfg.penLongIdle * c("long_idle") - max(0.0, idleShare - cfg.idleShareOk) * 100)
    subs["Soğuk motor"] = clamp(100 - cfg.penCold * c("cold_abuse"))
    val moving = (0 until n).filter { acc[it] != null && (S[it].speed ?: 0.0) > 5 }.map { abs(acc[it]!!) }
    val meanAbsA = if (moving.isNotEmpty()) moving.average() else 0.0
    subs["Hız istikrarı / Eko"] = clamp(100 - cfg.penUnstable * c("unstable_speed") / per10 -
        cfg.penPothole * c("pothole") / per10 - max(0.0, meanAbsA - cfg.ecoAccelOk) * 40)
    subs["Eğim yönetimi"] = clamp(100 - cfg.penLugUphill * c("lug_uphill") / per10 - cfg.penDescentBrake * c("descent_brake"))
    subs["Viraj"] = clamp(100 - cfg.penCorner * c("harsh_corner") / per10)
    val wsum = WEIGHTS.values.sum()
    val total = subs.entries.sumOf { it.value * WEIGHTS.getValue(it.key) } / wsum

    // --- ölçümler
    val speeds = S.mapNotNull { it.speed }
    val gearT = sortedMapOf<Int, Double>()
    for (i in 0 until n) S[i].gear?.let { if (it > 0) gearT[it] = (gearT[it] ?: 0.0) + dt[i] }
    metrics.durationS = Math.round(dur * 10) / 10.0
    metrics.distanceKm = Math.round(km * 100) / 100.0
    metrics.avgSpeed = if (dur > 0) Math.round(km / (dur / 3600) * 10) / 10.0 else 0.0
    metrics.maxSpeed = speeds.maxOrNull() ?: 0.0
    metrics.idleS = Math.round(idleS * 10) / 10.0
    metrics.idleShare = Math.round(idleShare * 1000) / 1000.0
    metrics.shiftCount = shifts.size
    metrics.upshiftCount = ups.size
    metrics.avgShiftRpm = avgShift?.roundToInt()
    metrics.meanAbsAccel = Math.round(meanAbsA * 100) / 100.0
    metrics.maxRpm = S.mapNotNull { it.rpm }.maxOrNull() ?: 0.0
    metrics.gearTime = gearT.mapValues { Math.round(it.value * 10) / 10.0 }
    metrics.counts = ev.groupingBy { it.type }.eachCount()
    metrics.maxLatG = S.mapNotNull { it.gLat }.maxOfOrNull { abs(it) } ?: 0.0
    metrics.maxLongG = S.mapNotNull { it.gLong }.maxOfOrNull { abs(it) } ?: 0.0
    metrics.maxVertG = S.mapNotNull { it.gVert }.maxOfOrNull { abs(it) } ?: 0.0
    terrainMetrics(S, dt, metrics)

    return ScoreResult(total.roundToInt(), subs.mapValues { it.value.roundToInt() }, ev.sortedBy { it.t }, metrics,
        km < 0.3 || dur < 60)
}

/** Yükseklik/eğim istatistikleri. [S] için annotateTerrain çağrılmış olmalı. */
fun terrainMetrics(S: List<Sample>, dt: DoubleArray, m: Metrics) {
    val alts = S.mapNotNull { it.alt }
    if (alts.size < 2) return
    m.hasAltitude = true
    val tr = ElevationTracker()
    alts.forEach { tr.update(it) }
    m.ascent = Math.round(tr.ascent * 10) / 10.0
    m.descent = Math.round(tr.descent * 10) / 10.0
    m.maxAlt = tr.maxAlt; m.minAlt = tr.minAlt
    val gr = S.mapNotNull { it.grade }
    if (gr.isNotEmpty()) { m.maxUpGrade = gr.max(); m.maxDownGrade = gr.min() }
    var up = 0.0; var flat = 0.0; var down = 0.0
    for (i in S.indices) {
        val g = S[i].grade ?: continue
        if (g > 2) up += dt[i] else if (g < -2) down += dt[i] else flat += dt[i]
    }
    val tot = up + flat + down
    if (tot > 0) { m.pctUp = up / tot * 100; m.pctFlat = flat / tot * 100; m.pctDown = down / tot * 100 }
}
