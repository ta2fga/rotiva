package com.ta2fga.rotiva.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Vites tahmini (desktop app/gear.py ile aynı algoritma).
 *
 * Oran r = hız (km/s) / (devir/1000). Devir ve hız ECU'dan AYRI isteklerle okunur; aralarındaki zaman farkı
 * ivmelenirken oranı bozduğu için [pushRpm]/[pushSpeed] örnekleri zamanca hizalar (hız anına devir
 * enterpolasyonu). Hizalanmış çiftler en yakın vites merkezine eşlenir.
 *
 * Öğrenme (model v3): kararlı sürüş pencerelerinden gelen oran gözlemleri log ölçekli bir histogramda birikir
 * (~%1,5 kutular, 4..70 km/s/1000 d/dk; toplam ağırlık sınırlı, eskiler orantılı söner). Her
 * [GearCfg.reestimateN] yeni gözlemde merkezler yeniden kestirilir: düzleştirilmiş histogramın tepeleri bulunur
 * ve "akla yatkın şanzıman" modeline göre vites numaralarına atanır (oranlar artan, ardışık adım 1,15..2,1;
 * varsayılan oranların ŞEKLİ tek bir ölçek s ∈ 0,6..1,6 ile ölçeklenir; verisi olan viteste gerçek tepe,
 * verisi olmayanda komşu düzeltmeleriyle şekilden doldurma). Tüm artan atamalar denenir, en düşük maliyetli
 * seçilir; böylece gerçek oranlar varsayılandan çok farklı olsa da (ör. ×1,25) vitesler doğru numaralanır.
 *
 * Sınıflama toleransı komşu merkezler arası log yarı aralığa göredir ([GearCfg.gapFrac]); tam ortadaki dar
 * bant (debriyaj kaydırma) tutarsızdır. Sonuç histerezisle
 * yayınlanır: yeni vites ~0,5 sn ve en az 2 örnek sürmeli; tutarsız (debriyaj/geçiş) örneklerde son vites
 * kısa süre korunur. "Boşta" yalnızca gerçekten tutarsızlık varsa (duruyor ya da hareket hâlinde rölanti)
 * verilir; veri yoksa/eskiyse sonuç "bilinmiyor"dur.
 */

/**
 * 2017 C-Elysée 1.6 HDi 92 hp, BE4 5 ileri: km/s per 1000 d/dk. Yalnızca başlangıç ve ŞEKİL; gerçek oranlar
 * veriden öğrenilir (araç bunlardan %60'a kadar farklı olabilir).
 */
val DEFAULT_RATIOS = doubleArrayOf(7.5, 14.0, 21.5, 29.0, 37.0)
const val GEAR_MODEL_VERSION = 3
const val NEUTRAL = 0
private val LD = DoubleArray(5) { ln(DEFAULT_RATIOS[it]) }                                // varsayılan şekil (log)
private val KER = DoubleArray(9) { val k = it - 4; exp(-0.5 * (k / 1.3) * (k / 1.3)) }   // düzleştirme çekirdeği

data class GearCfg(
    // eşleme
    val tol: Double = 0.10,            // uç vitesler (1. altı, 5. üstü) için oransal tolerans
    val gapFrac: Double = 0.75,        // komşu merkeze doğru: log yarı aralığın bu kadarı kabul (orta bant tutarsız)
    val tolMin: Double = 0.05,         // komşu tarafında en az bu kadar tolerans
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
    val learnedCount: Int = 40,        // bu kadar gözlemli tepe: vites "öğrenildi"
    // histogram + yeniden kestirim
    val histMin: Double = 4.0, val histMax: Double = 70.0, val histBin: Double = 0.015,   // log kutular (~%1,5)
    val histCap: Double = 6000.0,      // toplam ağırlık üst sınırı (aşılınca orantılı sönüm -> uyum)
    val reestimateN: Int = 25,         // bu kadar yeni gözlemde bir merkezler yeniden kestirilir
    val peakMin: Double = 8.0, val peakFrac: Double = 0.012,   // tepe kütlesi en az max(peakMin, peakFrac*toplam)
    val peakMerge: Double = 0.10,      // log uzaklığı bundan küçük tepeler birleştirilir
    val maxPeaks: Int = 7,
    val shapeSigma: Double = 0.09,     // tepe ile ölçeklenmiş varsayılan şekil arası log sapma ölçeği
    val dropCost: Double = 4.0,        // atanmayan tepe cezası (sigma^2 biriminde)
    val scaleMin: Double = 0.6, val scaleMax: Double = 1.6, val scaleSigma: Double = 0.3, val scalePriorW: Double = 1.0,
    val stepMin: Double = 1.15, val stepMax: Double = 2.1,      // ardışık vites adımı (r[i+1]/r[i]) akla yatkın aralığı
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
    private val lmin = ln(cfg.histMin)
    private val bw = ln(1 + cfg.histBin)
    private val nb = ceil((ln(cfg.histMax) - lmin) / bw).toInt()
    /** Log oran histogramı (kararlı gözlem ağırlıkları). */
    var hist = DoubleArray(nb)
        private set
    /** Son kestirimde varsayılan şekle göre ölçek. */
    var scale = 1.0
        private set
    private var pending = 0
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

    /** Akla yatkın şanzıman: aralık içinde, artan, ardışık adımlar makul (varsayılana bağlı DEĞİL). */
    private fun validRatios(r: DoubleArray?): Boolean {
        if (r == null || r.size != 5) return false
        for (i in 0 until 5) {
            if (r[i].isNaN() || r[i] < cfg.histMin || r[i] > cfg.histMax) return false
            if (i > 0 && (r[i] / r[i - 1] < 1.05 || r[i] / r[i - 1] > 2.6)) return false
        }
        return true
    }

    /** Kompakt: histogram yalnızca dolu aralık ("h0" = ilk kutu), 0,1 hassasiyetle. */
    @Synchronized fun toJson(): JSONObject {
        val nz = hist.indices.filter { hist[it] >= 0.05 }
        val h0 = nz.firstOrNull() ?: 0
        val h = JSONArray()
        if (nz.isNotEmpty()) for (i in h0..nz.last()) {
            val x = Math.round(hist[i] * 10) / 10.0
            if (x == floor(x)) h.put(x.toLong()) else h.put(x)
        }
        return JSONObject()
            .put("v", GEAR_MODEL_VERSION)
            .put("ratios", JSONArray(ratios.map { Math.round(it * 1000) / 1000.0 }))
            .put("counts", JSONArray(counts.toList()))
            .put("h0", h0).put("h", h)
    }

    @Synchronized fun reset() {
        ratios = DEFAULT_RATIOS.copyOf(); counts = IntArray(5); dirty = true
        hist = DoubleArray(nb); scale = 1.0; pending = 0
        win.clear(); aligner.clear(); state = null; cand = null; candN = 0
    }

    /** Bu vitesin merkezi doğrudan veriden (yeterli gözlemli tepe) mi geliyor? */
    fun learned(g: Int) = counts[g - 1] >= cfg.learnedCount

    /** En az 3 vites veriden öğrenildi: numaralama (hangi tepe kaçıncı vites) güvenilir. */
    val confident: Boolean get() = (1..5).count { learned(it) } >= 3

    /** (en yakın merkez indeksi, log sapma) */
    private fun nearest(r: Double): Pair<Int, Double> {
        val lr = ln(r)
        var bi = 0; var be = Double.MAX_VALUE
        for (k in 0 until 5) {
            val e = abs(lr - ln(ratios[k]))
            if (e < be) { be = e; bi = k }
        }
        return bi to (lr - ln(ratios[bi]))
    }

    /** i. merkezin d (log sapma) yönündeki toleransı: komşuya doğru yarı aralığın [GearCfg.gapFrac] kadarı. */
    private fun tolAt(i: Int, d: Double): Double {
        val j = i + if (d > 0) 1 else -1
        if (j < 0 || j > 4) return ln(1 + cfg.tol)
        val half = abs(ln(ratios[j] / ratios[i])) / 2
        return maxOf(ln(1 + cfg.tolMin), cfg.gapFrac * half)
    }

    /** Tek (hizalı) örnek: 1..5, [NEUTRAL] ya da null (tutarsız/bilinmiyor). */
    fun classify(rpm: Double?, speed: Double?): Int? {
        if (rpm == null || speed == null || rpm < cfg.minRpm) return null
        if (speed < cfg.stopSpeed) return NEUTRAL
        val (i, d) = nearest(speed / (rpm / 1000.0))
        if (abs(d) <= tolAt(i, d) + cfg.tolSpeedQ / speed) return i + 1
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

    /** Kararlı gözlemi log histograma ekler; her [GearCfg.reestimateN] gözlemde merkezleri yeniden kestirir. */
    private fun updateCluster(r: Double) {
        val x = (ln(r) - lmin) / bw - 0.5
        if (x < -0.5 || x > nb - 0.5) return
        val i0 = floor(x).toInt()
        val f = x - i0
        if (i0 in 0 until nb) hist[i0] += 1 - f
        if (i0 + 1 < nb) hist[i0 + 1] += f
        val tot = hist.sum()
        if (tot > cfg.histCap) {                       // orantılı sönüm: eski veri yavaşça unutulur
            val k = cfg.histCap / tot
            for (i in hist.indices) hist[i] *= k
        }
        pending++
        dirty = true
        if (pending >= cfg.reestimateN) reestimate()
    }

    /** Düzleştirilmiş histogram tepeleri: (log merkez, kütle) artan sırada. */
    private fun peaks(): List<Pair<Double, Double>> {
        val h = hist; val n = nb
        val sm = DoubleArray(n) { i -> var a = 0.0; for (k in -4..4) if (i + k in 0 until n) a += KER[k + 4] * h[i + k]; a }
        val thr = maxOf(cfg.peakMin, cfg.peakFrac * h.sum())
        val cand = ArrayList<Pair<Int, Double>>()
        for (i in 0 until n) {
            val left = if (i > 0) sm[i - 1] else 0.0
            val right = if (i < n - 1) sm[i + 1] else 0.0
            if (sm[i] > left && sm[i] >= right) {
                var m = 0.0
                for (j in maxOf(0, i - 3) until minOf(n, i + 4)) m += h[j]
                if (m >= thr) cand.add(i to m)
            }
        }
        val merge = cfg.peakMerge / bw                  // çok yakın tepeler birleşir (büyük olan kalır)
        val kept = ArrayList<Pair<Int, Double>>()
        for (p in cand.sortedWith(compareBy({ -it.second }, { it.first }))) {
            if (kept.all { abs(p.first - it.first) >= merge }) kept.add(p)
        }
        val ks = kept.take(cfg.maxPeaks).sortedBy { it.first }
        val out = ArrayList<Pair<Double, Double>>()
        for ((k, p) in ks.withIndex()) {               // merkez: komşu tepelerin ortasına kadar ağırlıklı ortalama
            val i = p.first
            val lo = if (k > 0) (ks[k - 1].first + i) / 2.0 else -1e9
            val hi = if (k < ks.size - 1) (ks[k + 1].first + i) / 2.0 else 1e9
            var w = 0.0; var wj = 0.0
            for (j in maxOf(0, i - 4) until minOf(n, i + 5)) if (j > lo && j < hi) { w += h[j]; wj += h[j] * j }
            if (w > 0) out.add(lmin + (wj / w + 0.5) * bw to w)
        }
        return out
    }

    private class Fit(val cost: Double, val ratios: DoubleArray, val scale: Double, val mass: DoubleArray)

    /** assign[j]: tepe j'nin vites indeksi (0..4) ya da -1 (atanmadı). Akla yatkın değilse null. */
    private fun fit(pk: List<Pair<Double, Double>>, assign: IntArray): Fit? {
        val w = DoubleArray(pk.size) { sqrt(pk[it].second) }
        var sw = 0.0; var sd = 0.0
        for (j in pk.indices) if (assign[j] >= 0) { sw += w[j]; sd += w[j] * (pk[j].first - LD[assign[j]]) }
        val ls = sd / (sw + cfg.scalePriorW)
        if (ls < ln(cfg.scaleMin) || ls > ln(cfg.scaleMax)) return null
        var cost = cfg.scalePriorW * (ls / cfg.scaleSigma) * (ls / cfg.scaleSigma)
        for (j in pk.indices) {
            if (assign[j] < 0) cost += cfg.dropCost * w[j]
            else { val e = (pk[j].first - LD[assign[j]] - ls) / cfg.shapeSigma; cost += w[j] * e * e }
        }
        // vites başına log düzeltme: veri varsa tepenin kendisi, yoksa komşu düzeltmelerinden (şekil korunur)
        val corr = DoubleArray(5) { Double.NaN }
        val mass = DoubleArray(5)
        for (j in pk.indices) if (assign[j] >= 0) { corr[assign[j]] = pk[j].first - LD[assign[j]]; mass[assign[j]] = pk[j].second }
        val known = (0 until 5).filter { !corr[it].isNaN() }
        val full = corr.copyOf()
        for (g in 0 until 5) {
            if (!full[g].isNaN()) continue
            val lo = known.lastOrNull { it < g }
            val hi = known.firstOrNull { it > g }
            full[g] = when {
                lo != null && hi != null -> corr[lo] + (corr[hi] - corr[lo]) * (g - lo) / (hi - lo)
                lo != null -> corr[lo]
                else -> corr[hi!!]
            }
        }
        val r = DoubleArray(5) { exp(LD[it] + full[it]) }
        for (g in 1 until 5) { val st = r[g] / r[g - 1]; if (st < cfg.stepMin || st > cfg.stepMax) return null }
        return Fit(cost, r, exp(ls), mass)
    }

    /** Histogramdan 5 merkezi yeniden kestirir (tüm artan atamalar denenir); değiştiyse true. */
    @Synchronized fun reestimate(): Boolean {
        pending = 0
        val pk = peaks()
        if (pk.isEmpty()) return false
        var best: Fit? = null
        val assign = IntArray(pk.size) { -1 }
        fun rec(j: Int, nextG: Int) {
            if (j == pk.size) {
                if (nextG > 0) fit(pk, assign)?.let { f -> if (best == null || f.cost < best!!.cost) best = f }
                return
            }
            assign[j] = -1
            rec(j + 1, nextG)
            for (g in nextG until 5) { assign[j] = g; rec(j + 1, g + 1) }
            assign[j] = -1
        }
        rec(0, 0)
        val b = best ?: return false
        val changed = (0 until 5).any { abs(b.ratios[it] - ratios[it]) / ratios[it] > 1e-4 }
        ratios = b.ratios; scale = b.scale
        counts = IntArray(5) { b.mass[it].roundToInt() }
        dirty = true
        return changed
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
            // eski (varsayılana bağlı, v1/v2) öğrenme atılır
            if (j == null || j.optInt("v", 1) != GEAR_MODEL_VERSION) return GearEstimator()
            val r = j.optJSONArray("ratios")?.let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
            val c = j.optJSONArray("counts")?.let { a -> IntArray(a.length()) { a.getInt(it) } }
            val g = GearEstimator(r, c)
            val h0 = j.optInt("h0", 0)
            j.optJSONArray("h")?.let { a ->
                for (i in 0 until a.length()) if (h0 + i in g.hist.indices) g.hist[h0 + i] = maxOf(0.0, a.optDouble(i, 0.0))
            }
            return g
        }
    }
}

fun gearLabel(state: Int?): String = when (state) { null -> "—"; NEUTRAL -> "N"; else -> state.toString() }
