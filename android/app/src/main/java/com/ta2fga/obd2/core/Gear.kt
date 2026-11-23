package com.ta2fga.obd2.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Vites tahmini (desktop app/gear.py): oran r = hız (km/s) / (devir/1000). Oranlar çevrimiçi 1-B kümeleme ile
 * öğrenilir; başlangıç değerleri TAHMİNDİR ve kararlı örneklerle araca göre düzeltilir.
 */
val DEFAULT_RATIOS = doubleArrayOf(8.0, 15.0, 22.5, 30.0, 38.0)

data class GearCfg(
    val windowS: Double = 1.0,
    val minRpm: Double = 900.0, val minSpeed: Double = 8.0,
    val maxDSpeed: Double = 6.0, val maxDRpm: Double = 600.0,
    val maxSpread: Double = 0.03, val tol: Double = 0.07,
    val idleRpmMax: Double = 900.0, val minMoving: Double = 3.0,
    val assignRadius: Double = 0.20,
    val countCap: Int = 300, val prior: Int = 5, val minSep: Double = 0.08, val learnedCount: Int = 40,
    val debounce: Int = 2, val gapResetS: Double = 8.0,
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

class GearEstimator(ratios: DoubleArray? = null, counts: IntArray? = null, val cfg: GearCfg = GearCfg()) {
    var ratios: DoubleArray = if (ratios != null && ratios.size == 5) ratios.copyOf() else DEFAULT_RATIOS.copyOf()
    var counts: IntArray = if (counts != null && counts.size == 5) counts.copyOf() else IntArray(5)
    private val win = ArrayDeque<Triple<Double, Double, Double>>()
    var gear: Int? = null
        private set
    private var cand: Int? = null
    private var candN = 0
    val tracker = ShiftTracker(cfg.gapResetS)
    var dirty = false

    val shifts get() = tracker.shifts

    fun toJson(): JSONObject = JSONObject()
        .put("ratios", JSONArray(ratios.map { Math.round(it * 1000) / 1000.0 }))
        .put("counts", JSONArray(counts.toList()))

    fun reset() { ratios = DEFAULT_RATIOS.copyOf(); counts = IntArray(5); dirty = true }

    fun learned(g: Int) = counts[g - 1] >= cfg.learnedCount

    private fun nearest(r: Double): Pair<Int, Double> {
        var bi = 0; var be = Double.MAX_VALUE
        for (k in 0 until 5) {
            val e = abs(r - ratios[k]) / ratios[k]
            if (e < be) { be = e; bi = k }
        }
        return bi to be
    }

    fun classify(rpm: Double?, speed: Double?): Int? {
        if (rpm == null || speed == null || speed < cfg.minMoving || rpm <= 0) return null
        if (rpm < cfg.idleRpmMax) return null
        val (i, err) = nearest(speed / (rpm / 1000.0))
        return if (err <= cfg.tol) i + 1 else null
    }

    private fun learn(t: Double) {
        val w = win.filter { t - it.first <= cfg.windowS }
        if (w.size < 3 || w.last().first - w.first().first < cfg.windowS * 0.7) return
        val rp = w.map { it.second }
        val sp = w.map { it.third }
        val mr = rp.average(); val ms = sp.average()
        if (mr <= cfg.minRpm || ms <= cfg.minSpeed) return
        if (sp.max() - sp.min() > cfg.maxDSpeed || rp.max() - rp.min() > cfg.maxDRpm) return
        val rs = w.filter { it.second > 0 }.map { it.third / (it.second / 1000.0) }
        if (rs.isEmpty() || (rs.max() - rs.min()) / rs.average() > cfg.maxSpread + 1.0 / ms) return
        updateCluster(ms / (mr / 1000.0))
    }

    private fun updateCluster(r: Double) {
        val (i, err) = nearest(r)
        if (err > cfg.assignRadius) return
        counts[i] += 1
        val n = minOf(counts[i] + cfg.prior, cfg.countCap)
        val nw = ratios[i] + (r - ratios[i]) / n
        val lo = if (i > 0) ratios[i - 1] * (1 + cfg.minSep) else 0.0
        val hi = if (i < 4) ratios[i + 1] / (1 + cfg.minSep) else 1e9
        if (nw > lo && nw < hi) { ratios[i] = nw; dirty = true }
    }

    fun update(t: Double, rpm: Double?, speed: Double?): Int? {
        if (rpm == null || speed == null) return gear
        win.addLast(Triple(t, rpm, speed))
        while (win.size > 64) win.removeFirst()
        learn(t)
        val raw = classify(rpm, speed)
        if (raw == gear) { cand = null; candN = 0 }
        else {
            if (raw == cand) candN++ else { cand = raw; candN = 1 }
            if (candN >= cfg.debounce) { gear = raw; cand = null; candN = 0 }
        }
        tracker.update(t, rpm, gear)
        return gear
    }

    companion object {
        fun fromJson(j: JSONObject?): GearEstimator {
            if (j == null) return GearEstimator()
            val r = j.optJSONArray("ratios")?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            val c = j.optJSONArray("counts")?.let { a -> IntArray(a.length()) { a.getInt(it) } }
            return GearEstimator(r, c)
        }
    }
}
