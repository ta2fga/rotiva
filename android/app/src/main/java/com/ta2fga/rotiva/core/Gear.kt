package com.ta2fga.rotiva.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Vites tahmini (desktop app/gear.py ile aynı algoritma).
 *
 * Oran r = hız (km/s) / (devir/1000). Devir ve hız ECU'dan AYRI isteklerle okunur; aralarındaki zaman farkı
 * ivmelenirken oranı bozduğu için [pushRpm]/[pushSpeed] örnekleri zamanca hizalar (hız anına devir
 * enterpolasyonu). Hizalanmış çiftler en yakın vites merkezine eşlenir; merkezler kararlı sürüşte çevrimiçi
 * kümeleme ile araca göre düzeltilir (varsayılanın ±%[GearCfg.bound] içinde kalır). Sonuç histerezisle
 * yayınlanır: yeni vites ~0,5 sn ve en az 2 örnek sürmeli; tutarsız (debriyaj/geçiş) örneklerde son vites
 * kısa süre korunur. "Boşta" yalnızca gerçekten tutarsızlık varsa (duruyor ya da hareket hâlinde rölanti)
 * verilir; veri yoksa/eskiyse sonuç "bilinmiyor"dur.
 */

/** 2017 C-Elysée 1.6 HDi 92 hp, BE4 5 ileri: km/s per 1000 d/dk (yaklaşık; kullanıldıkça öğrenilir). */
val DEFAULT_RATIOS = doubleArrayOf(7.5, 14.0, 21.5, 29.0, 37.0)
const val GEAR_MODEL_VERSION = 2
const val NEUTRAL = 0

data class GearCfg(
    // eşleme
    val tol: Double = 0.10,            // merkeze göre oransal tolerans
    val tolSpeedQ: Double = 0.5,       // + tolSpeedQ/hız (OBD hızı tam sayı km/s)
    val minRpm: Double = 450.0,        // altı: motor çalışmıyor -> bilinmiyor
    val idleRpmMax: Double = 1050.0,   // rölanti bandı (dizel ~800-850)
    val stopSpeed: Double = 3.0,       // altı: duruyor -> boşta
    val coastSpeed: Double = 5.0,      // üstünde rölanti + uyumsuz oran -> boşta/debriyaj
    // zaman hizalama
    val maxSkew: Double = 0.35,        // en yakın devir örneği en fazla bu kadar uzak olabilir (sn)
    val maxBracket: Double = 1.5,      // enterpolasyon için iki devir örneği arası en fazla (sn)
    // histerezis
    val holdS: Double = 0.5, val holdN: Int = 2,
    val unknownHoldS: Double = 2.0,    // tutarsız örneklerde son durumu koruma süresi
    val staleS: Double = 3.0,          // bu süre hizalı örnek gelmezse bilinmiyor
    // öğrenme
    val windowS: Double = 1.5, val minLearnRpm: Double = 1100.0, val minLearnSpeed: Double = 8.0,
    val maxDSpeed: Double = 8.0, val maxDRpm: Double = 1000.0, val maxSpread: Double = 0.03,
    val assignRadius: Double = 0.10, val bound: Double = 0.18,
    val countCap: Int = 300, val prior: Int = 5, val minSep: Double = 0.12, val learnedCount: Int = 40,
    val gapResetS: Double = 8.0,
)

data class Shift(val t: Double, val fromGear: Int, val toGear: Int, val rpm: Double) {
    val up get() = toGear > fromGear
}

class ShiftTracker(private val gap: Double = GearCfg().gapResetS) {
    var last: Int? = null
    var lastT = 0.0
    private val recent = ArrayDeque<Pair<Double, Double?>>()
    val shifts = ArrayList<Shift>()

    fun update(t: Double, rpm: Double?, gear: Int?): Shift? {
        if (gear == null) {
            if (last != null && t - lastT > gap) { last = null; recent.clear() }
            return null
        }
        var out: Shift? = null
        val l = last
        if (l != null && gear != l && t - lastT <= gap) {
            val pk = recent.filter { lastT - it.first <= 1.5 && it.second != null }.map { it.second!! }
            out = Shift(t, l, gear, if (pk.isNotEmpty()) pk.max() else (rpm ?: 0.0))
            shifts.add(out)
        }
        if (gear != l) recent.clear()
        recent.addLast(t to rpm)
        while (recent.size > 12) recent.removeFirst()
        last = gear; lastT = t
        return out
    }
}

fun detectShifts(seq: List<Triple<Double, Double?, Int?>>): List<Shift> {
    val tr = ShiftTracker()
    for ((t, rpm, g) in seq) tr.update(t, rpm, g)
    return tr.shifts
}

/** Ayrı zamanlarda okunan devir ve hız örneklerinden zamanca hizalı (t, devir, hız) çiftleri üretir. */
class PairAligner(private val maxSkew: Double = 0.35, private val maxBracket: Double = 1.5) {
    private val rpm = ArrayDeque<Pair<Double, Double>>()
    private val pend = ArrayDeque<Pair<Double, Double>>()
    private var latest = 0.0

    fun clear() { rpm.clear(); pend.clear(); latest = 0.0 }

    fun pushRpm(t: Double, v: Double): List<Triple<Double, Double, Double>> {
        rpm.addLast(t to v); while (rpm.size > 16) rpm.removeFirst()
        latest = maxOf(latest, t)
        return resolve()
    }

    fun pushSpeed(t: Double, v: Double): List<Triple<Double, Double, Double>> {
        pend.addLast(t to v); while (pend.size > 16) pend.removeFirst()
        latest = maxOf(latest, t)
        return resolve()
    }

    private fun nearest(t: Double): Pair<Double, Double>? =
        rpm.minByOrNull { abs(it.first - t) }?.takeIf { abs(it.first - t) <= maxSkew }

    private fun resolve(): List<Triple<Double, Double, Double>> {
        val out = ArrayList<Triple<Double, Double, Double>>()
        while (pend.isNotEmpty()) {
            val (ts, sp) = pend.first()
            val before = rpm.lastOrNull { it.first <= ts }
            val after = rpm.firstOrNull { it.first >= ts }
            when {
                before != null && after != null -> {
                    pend.removeFirst()
                    val span = after.first - before.first
                    if (span <= 1e-9) out.add(Triple(ts, before.second, sp))
                    else if (span <= maxBracket) {
                        val f = (ts - before.first) / span
                        out.add(Triple(ts, before.second + (after.second - before.second) * f, sp))
                    } else nearest(ts)?.let { out.add(Triple(ts, it.second, sp)) }
                }
                after == null -> {
                    // sonraki devir örneği henüz gelmedi: bekle; çok gecikirse en yakınla eşle ya da at
                    if (latest - ts <= maxBracket) break
                    pend.removeFirst()
                    nearest(ts)?.let { out.add(Triple(ts, it.second, sp)) }
                }
                else -> { pend.removeFirst(); nearest(ts)?.let { out.add(Triple(ts, it.second, sp)) } }
            }
        }
        return out
    }
}

class GearEstimator(ratios: DoubleArray? = null, counts: IntArray? = null, val cfg: GearCfg = GearCfg()) {
    var ratios: DoubleArray = if (validRatios(ratios)) ratios!!.copyOf() else DEFAULT_RATIOS.copyOf()
    var counts: IntArray = if (validRatios(ratios) && counts != null && counts.size == 5) counts.copyOf() else IntArray(5)
    private val win = ArrayDeque<Triple<Double, Double, Double>>()
    private val aligner = PairAligner(cfg.maxSkew, cfg.maxBracket)

    /** Yayınlanan durum: 1..5 vites, [NEUTRAL] boşta/debriyaj, null bilinmiyor. */
    var state: Int? = null
        private set
    private var cand: Int? = null
    private var candN = 0
    private var candT0 = 0.0
    private var lastConsistentT = -1e9
    var lastPairT = -1e9
        private set
    val tracker = ShiftTracker(cfg.gapResetS)
    var dirty = false

    /** Kayıt/puanlama için vites (boşta ve bilinmiyor = null). */
    val gear: Int? get() = state?.takeIf { it >= 1 }
    val neutral: Boolean get() = state == NEUTRAL
    val shifts get() = tracker.shifts

    private fun validRatios(r: DoubleArray?): Boolean {
        if (r == null || r.size != 5) return false
        for (i in 0 until 5) {
            if (r[i].isNaN() || abs(r[i] - DEFAULT_RATIOS[i]) / DEFAULT_RATIOS[i] > cfg.bound + 1e-9) return false
            if (i > 0 && r[i] <= r[i - 1]) return false
        }
        return true
    }

    @Synchronized fun toJson(): JSONObject = JSONObject()
        .put("v", GEAR_MODEL_VERSION)
        .put("ratios", JSONArray(ratios.map { Math.round(it * 1000) / 1000.0 }))
        .put("counts", JSONArray(counts.toList()))

    @Synchronized fun reset() {
        ratios = DEFAULT_RATIOS.copyOf(); counts = IntArray(5); dirty = true
        win.clear(); aligner.clear(); state = null; cand = null; candN = 0
    }

    fun learned(g: Int) = counts[g - 1] >= cfg.learnedCount

    private fun nearest(r: Double): Pair<Int, Double> {
        var bi = 0; var be = Double.MAX_VALUE
        for (k in 0 until 5) {
            val e = abs(r - ratios[k]) / ratios[k]
            if (e < be) { be = e; bi = k }
        }
        return bi to be
    }

    /** Tek (hizalı) örnek: 1..5, [NEUTRAL] ya da null (tutarsız/bilinmiyor). */
    fun classify(rpm: Double?, speed: Double?): Int? {
        if (rpm == null || speed == null || rpm < cfg.minRpm) return null
        if (speed < cfg.stopSpeed) return NEUTRAL
        val (i, err) = nearest(speed / (rpm / 1000.0))
        if (err <= cfg.tol + cfg.tolSpeedQ / speed) return i + 1
        if (rpm <= cfg.idleRpmMax && speed >= cfg.coastSpeed) return NEUTRAL
        return null
    }

    private fun learn(t: Double) {
        while (win.isNotEmpty() && t - win.first().first > cfg.windowS) win.removeFirst()
        val w = win.toList()
        if (w.size < 3 || w.last().first - w.first().first < cfg.windowS * 0.6) return
        val rp = w.map { it.second }
        val sp = w.map { it.third }
        val mr = rp.average(); val ms = sp.average()
        if (mr < cfg.minLearnRpm || ms < cfg.minLearnSpeed) return
        if (sp.max() - sp.min() > cfg.maxDSpeed || rp.max() - rp.min() > cfg.maxDRpm) return
        val rs = w.map { it.third / (it.second / 1000.0) }
        if ((rs.max() - rs.min()) / rs.average() > cfg.maxSpread + 1.0 / ms) return
        updateCluster(ms / (mr / 1000.0))
    }

    private fun updateCluster(r: Double) {
        val (i, err) = nearest(r)
        if (err > cfg.assignRadius) return
        counts[i] += 1
        val n = minOf(counts[i] + cfg.prior, cfg.countCap)
        val nw = ratios[i] + (r - ratios[i]) / n
        val lo = maxOf(if (i > 0) ratios[i - 1] * (1 + cfg.minSep) else 0.0, DEFAULT_RATIOS[i] * (1 - cfg.bound))
        val hi = minOf(if (i < 4) ratios[i + 1] / (1 + cfg.minSep) else 1e9, DEFAULT_RATIOS[i] * (1 + cfg.bound))
        if (nw > lo && nw < hi) { ratios[i] = nw; dirty = true }
    }

    /** Zaman damgalı devir örneği (ayrı okunan hız ile hizalanır). */
    @Synchronized fun pushRpm(t: Double, rpm: Double): Int? {
        for ((pt, r, s) in aligner.pushRpm(t, rpm)) step(pt, r, s)
        return gear
    }

    /** Zaman damgalı hız örneği. */
    @Synchronized fun pushSpeed(t: Double, speed: Double): Int? {
        for ((pt, r, s) in aligner.pushSpeed(t, speed)) step(pt, r, s)
        return gear
    }

    /** Hizalanmış çift. [skew]: devir ve hız örnekleri arasındaki süre (bilinirse); çok büyükse yok sayılır. */
    @Synchronized fun update(t: Double, rpm: Double?, speed: Double?, skew: Double = 0.0): Int? {
        if (rpm == null || speed == null || abs(skew) > cfg.maxSkew) return gearAt(t)
        step(t, rpm, speed)
        return gear
    }

    private fun step(t: Double, rpm: Double, speed: Double) {
        lastPairT = t
        if (rpm >= cfg.minRpm && speed > 0) {
            win.addLast(Triple(t, rpm, speed))
            while (win.size > 64) win.removeFirst()
            learn(t)
        } else win.clear()
        val raw = classify(rpm, speed)
        if (raw == null) {
            cand = null; candN = 0
            if (state != null && t - lastConsistentT > cfg.unknownHoldS) state = null
        } else {
            lastConsistentT = t
            if (raw == state) { cand = null; candN = 0 }
            else {
                if (raw == cand) candN++ else { cand = raw; candN = 1; candT0 = t }
                if (candN >= cfg.holdN && (state == null || t - candT0 >= cfg.holdS)) { state = raw; cand = null; candN = 0 }
            }
        }
        tracker.update(t, rpm, gear)
    }

    /** [now] anındaki yayınlanabilir durum (hizalı veri eskiyse null = bilinmiyor). */
    @Synchronized fun stateAt(now: Double): Int? = if (now - lastPairT > cfg.staleS) null else state
    fun gearAt(now: Double): Int? = stateAt(now)?.takeIf { it >= 1 }

    /** Gösterim: "1".."5", "N" (boşta/debriyaj) ya da "—" (bilinmiyor). */
    fun label(now: Double): String = gearLabel(stateAt(now))

    companion object {
        fun fromJson(j: JSONObject?): GearEstimator {
            if (j == null || j.optInt("v", 1) != GEAR_MODEL_VERSION) return GearEstimator()   // eski (hizasız) öğrenme atılır
            val r = j.optJSONArray("ratios")?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            val c = j.optJSONArray("counts")?.let { a -> IntArray(a.length()) { a.getInt(it) } }
            return GearEstimator(r, c)
        }
    }
}

fun gearLabel(state: Int?): String = when (state) { null -> "—"; NEUTRAL -> "N"; else -> state.toString() }
