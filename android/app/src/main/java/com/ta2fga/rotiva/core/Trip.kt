package com.ta2fga.rotiva.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sürüş kaydedici (desktop app/trip.py). Sürüş yalnızca kullanıcı bitirince sona erer; duruşlar rölanti sayılır.
 * Veri akışı kesilirse kayıt duraklar, tekrar gelince aynı sürüşe devam eder. Her [autosaveS] sn'de artımlı kayıt.
 */
class TripCfg(
    var autoStart: Boolean = true, val autoStartKmh: Double = 5.0, val autosaveS: Double = 30.0,
    val minSamples: Int = 10, val prebuffer: Int = 40, val stallS: Double = 10.0,
)

val FIELDS = listOf("t", "rpm", "speed", "load", "coolant", "map", "maf", "frp", "gear", "lat", "lon", "gpsSpeed",
    "bearing", "alt", "acc", "baroAlt", "gLong", "gLat", "gVert")   // eski CSV'lerde gVert yoktur -> null

private fun fmt(v: Double?, d: Int = 3): String = if (v == null || v.isNaN()) "" else if (d == 7) "%.7f".format(Locale.US, v) else "%.${d}f".format(Locale.US, v)

fun sampleToCsv(s: Sample): String = listOf(
    "%.2f".format(Locale.US, s.t), fmt(s.rpm, 1), fmt(s.speed, 2), fmt(s.load, 1), fmt(s.coolant, 1), fmt(s.map, 1),
    fmt(s.maf, 2), fmt(s.frp, 1), s.gear?.toString() ?: "", fmt(s.lat, 7), fmt(s.lon, 7), fmt(s.gpsSpeed, 2),
    fmt(s.bearing, 1), fmt(s.alt, 2), fmt(s.acc, 1), fmt(s.baroAlt, 2), fmt(s.gLong, 4), fmt(s.gLat, 4),
    fmt(s.gVert, 3),
).joinToString(",")

fun sampleFromCsv(cols: List<String>, idx: Map<String, Int>): Sample? {
    fun d(k: String): Double? = idx[k]?.let { cols.getOrNull(it) }?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val t = d("t") ?: return null
    return Sample(t, d("rpm"), d("speed"), d("load"), d("coolant"), d("map"), d("maf"), d("frp"), d("gear")?.toInt(),
        d("lat"), d("lon"), d("gpsSpeed"), d("bearing"), d("alt"), d("acc"), d("baroAlt"), d("gLong"), d("gLat"),
        d("gVert"))
}

fun readSamples(f: File): List<Sample> {
    val out = ArrayList<Sample>()
    if (!f.exists()) return out
    f.useLines { lines ->
        var idx: Map<String, Int>? = null
        for (ln in lines) {
            if (ln.isBlank()) continue
            val cols = ln.split(",")
            if (idx == null) { idx = cols.withIndex().associate { it.value to it.index }; continue }
            sampleFromCsv(cols, idx)?.let { out.add(it) }
        }
    }
    return out
}

class TripStore(val dir: File) {
    init { dir.mkdirs() }

    fun csv(id: String) = File(dir, "$id.csv")
    fun json(id: String) = File(dir, "$id.json")

    fun list(): List<JSONObject> = (dir.listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".json") && !it.name.startsWith(UNF) }
        .mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
        .sortedByDescending { it.optDouble("start_ts", 0.0) }

    fun delete(id: String) { csv(id).delete(); json(id).delete() }
    fun loadSamples(id: String) = readSamples(csv(id))

    companion object { const val UNF = "unfinished" }
}

class TripRecorder(
    val store: TripStore, val cfg: TripCfg = TripCfg(), var gear: GearEstimator = GearEstimator(),
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    val samples = ArrayList<Sample>()
    private val pre = ArrayDeque<Sample>()
    @Volatile var active = false; private set
    @Volatile var paused = false; private set
    val pauses = ArrayList<Pair<Double, Double>>()
    private var pauseT = 0.0
    var startTs = 0.0; private set
    var lastT = 0.0; private set
    private var flushed = 0
    private var lastSave = 0.0
    var lastSummary: JSONObject? = null

    private val unfCsv get() = store.csv(TripStore.UNF)
    private val unfJson get() = store.json(TripStore.UNF)

    fun start(t: Double = clock(), withPrebuffer: Boolean = false) {
        if (active) return
        samples.clear()
        if (withPrebuffer) samples.addAll(pre)
        pre.clear()
        active = true; paused = false; pauses.clear()
        startTs = samples.firstOrNull()?.t ?: t
        lastT = t; lastSave = t; flushed = 0
        removeUnfinished()
        flush(t)
    }

    fun pause(t: Double = clock()) {
        if (active && !paused) { paused = true; pauseT = if (lastT != 0.0) lastT else t }
    }

    /** Bir örnek işler. Olay: "started" | "resumed" | null. */
    fun feed(s: Sample): String? {
        val t = s.t
        if (s.rpm == null && s.speed == null) return null
        var ev: String? = null
        if (!active) {
            pre.addLast(s)
            while (pre.size > cfg.prebuffer) pre.removeFirst()
            if (cfg.autoStart && (s.speed ?: 0.0) > cfg.autoStartKmh) { start(t, true); ev = "started" }
            return ev
        }
        if (paused) { paused = false; pauses.add(pauseT to t); ev = "resumed" }
        samples.add(s)
        lastT = t
        if (t - lastSave >= cfg.autosaveS) flush(t)
        return ev
    }

    fun stalled(now: Double) = active && !paused && now - lastT > cfg.stallS

    private fun removeUnfinished() { unfCsv.delete(); unfJson.delete() }

    /** Artımlı kayıt: yeni satırları unfinished.csv'ye ekler, üst veriyi yeniler. */
    fun flush(t: Double = clock()) {
        try {
            val new = samples.subList(flushed, samples.size).toList()
            val f = unfCsv
            val header = flushed == 0 || !f.exists() || f.length() == 0L
            f.appendText((if (header) FIELDS.joinToString(",") + "\n" else "") + new.joinToString("") { sampleToCsv(it) + "\n" })
            flushed = samples.size
            unfJson.writeText(JSONObject().put("start_ts", startTs).put("saved_at", t)
                .put("pauses", pausesJson()).put("gear", gear.toJson()).toString())
        } catch (_: Exception) {
        }
        lastSave = t
    }

    private fun pausesJson() = JSONArray(pauses.map { JSONArray(listOf(it.first, it.second)) })

    class Unfinished(val startTs: Double, val samples: List<Sample>, val pauses: List<Pair<Double, Double>>)

    fun findUnfinished(): Unfinished? {
        if (!unfCsv.exists() || !unfJson.exists()) return null
        return try {
            val meta = JSONObject(unfJson.readText())
            val s = readSamples(unfCsv)
            if (s.size < cfg.minSamples) return null
            val pj = meta.optJSONArray("pauses") ?: JSONArray()
            Unfinished(meta.getDouble("start_ts"), s, (0 until pj.length()).map { pj.getJSONArray(it).let { a -> a.getDouble(0) to a.getDouble(1) } })
        } catch (_: Exception) { null }
    }

    /** Yarım kalan sürüşü yeniden açar; sonraki veriyle devam eder (aradaki boşluk duraklama sayılır). */
    fun resumeUnfinished(u: Unfinished) {
        samples.clear(); samples.addAll(u.samples)
        startTs = u.startTs
        pauses.clear(); pauses.addAll(u.pauses)
        active = true; paused = true
        lastT = samples.last().t; pauseT = lastT
        flushed = samples.size
        lastSave = clock()
    }

    fun discardUnfinished() = removeUnfinished()

    /** Sürüşü bitirir; CSV + JSON özetini yazar. Çok kısaysa kaydetmez (null). */
    fun stop(): JSONObject? {
        if (!active) return null
        val S = ArrayList(samples)
        active = false; paused = false
        samples.clear()
        removeUnfinished()
        if (S.size < cfg.minSamples || S.maxOf { it.speed ?: 0.0 } <= 0.0) return null
        val res = analyze(S)
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date((S[0].t * 1000).toLong()))
        val summary = buildSummary(id, S, res, pauses, gear)
        return try {
            store.csv(id).bufferedWriter().use { w ->
                w.write(FIELDS.joinToString(",") + "\n")
                S.forEach { w.write(sampleToCsv(it) + "\n") }
            }
            store.json(id).writeText(summary.toString(1))
            lastSummary = summary
            summary
        } catch (_: Exception) { null }
    }
}

fun buildSummary(id: String, S: List<Sample>, res: ScoreResult, pauses: List<Pair<Double, Double>>, gear: GearEstimator): JSONObject {
    val m = res.metrics
    val tips = coachTips(res)
    val (gr, gc) = gear.snapshot()          // aynı uzunlukta (kayıt sırasında vites sayısı değişebilir)
    return JSONObject()
        .put("id", id).put("start_ts", S.first().t).put("end_ts", S.last().t)
        .put("pauses", JSONArray(pauses.map { JSONArray(listOf(it.first, it.second)) }))
        .put("total", res.total ?: JSONObject.NULL)
        .put("subs", JSONObject(res.subs as Map<*, *>))
        .put("short", res.short)
        .put("events", JSONArray(res.events.map {
            JSONObject().put("type", it.type).put("label", it.label).put("t", it.t).put("text", it.text).put("value", it.value)
        }))
        .put("tips", JSONArray(tips.map { JSONObject().put("kind", it.kind).put("text", it.text) }))
        .put("metrics", JSONObject()
            .put("duration_s", m.durationS).put("distance_km", m.distanceKm).put("avg_speed", m.avgSpeed)
            .put("max_speed", m.maxSpeed).put("ascent", m.ascent).put("descent", m.descent)
            .put("has_altitude", m.hasAltitude).put("max_alt", m.maxAlt ?: JSONObject.NULL)
            .put("min_alt", m.minAlt ?: JSONObject.NULL))
        .put("ratios", JSONArray(gr.map { Math.round(it * 100) / 100.0 }))
        .put("gear_counts", JSONArray(gc.toList()))
}
