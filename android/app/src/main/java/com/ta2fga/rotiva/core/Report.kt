package com.ta2fga.rotiva.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Rapor verisi: puan, olaylar, ipuçları ve grafik serileri (uygulama içi çizim ve HTML dışa aktarım ortak kullanır). */
class TripReport(
    val samples: List<Sample>, val result: ScoreResult, val tips: List<Tip>,
    val startTs: Double, val endTs: Double,
) {
    val m get() = result.metrics
    /** (mesafe km, yükseklik m) */
    val altProfile: List<Pair<Double, Double>> by lazy {
        thin(samples.filter { it.alt != null }.map { it.dist / 1000 to it.alt!! }, 400)
    }
    /** (t sn, hız, devir, vites) */
    class TsPoint(val t: Double, val speed: Double?, val rpm: Double?, val gear: Int?)
    val series: List<TsPoint> by lazy {
        val t0 = samples.firstOrNull()?.t ?: 0.0
        thin(samples.map { TsPoint(it.t - t0, it.speed, it.rpm, it.gear) }, 600)
    }
    val track: List<TrackPoint> by lazy {
        val t0 = samples.firstOrNull()?.t ?: 0.0
        thin(samples, 600).mapIndexed { i, s ->
            TrackPoint(i, s.t - t0, s.dist / 1000, s.alt, s.grade, s.lat, s.lon, s.speed, s.rpm, s.gear)
        }
    }

    /** Olay + konum (en yakın örneğin enlem/boylamı). */
    class EventPos(val event: Event, val lat: Double, val lon: Double)
    val eventPositions: List<EventPos> by lazy {
        val ts = samples.map { it.t }
        result.events.mapNotNull { e ->
            val s = samples.getOrNull(nearestIndex(ts, e.ts))
            if (s?.lat != null && s.lon != null) EventPos(e, s.lat!!, s.lon!!) else null
        }
    }

    val route: List<Pair<Double, Double>> by lazy {
        thin(samples.filter { it.lat != null && it.lon != null }.map { it.lat!! to it.lon!! }, 400)
    }

    fun osmLink(): String? = route.firstOrNull()?.let { "https://www.openstreetmap.org/?mlat=${"%.5f".format(Locale.US, it.first)}&mlon=${"%.5f".format(Locale.US, it.second)}#map=14/${"%.5f".format(Locale.US, it.first)}/${"%.5f".format(Locale.US, it.second)}" }

    companion object {
        fun build(samples: List<Sample>, cfg: ScoreCfg = DEFAULT_CFG): TripReport {
            val S = samples.filter { it.speed != null || it.rpm != null }
            val res = analyze(S, cfg)
            return TripReport(S, res, coachTips(res, cfg), S.firstOrNull()?.t ?: 0.0, S.lastOrNull()?.t ?: 0.0)
        }
    }
}

fun <T> thin(l: List<T>, max: Int): List<T> {
    if (l.size <= max) return l
    val step = l.size.toDouble() / max
    return List(max) { l[(it * step).toInt()] }
}

fun fmtDuration(s: Double): String {
    val t = s.roundToInt(); val h = t / 3600; val mi = (t % 3600) / 60; val sec = t % 60
    return if (h > 0) "%d sa %02d dk".format(h, mi) else "%d dk %02d sn".format(mi, sec)
}

fun fmtDate(ts: Double): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("tr-TR")).format(Date((ts * 1000).toLong()))

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
private fun f(d: Double) = "%.1f".format(Locale.US, d)

private fun polyline(pts: List<Pair<Double, Double>>, w: Double, h: Double, color: String, fill: Boolean = false,
                     xr: Pair<Double, Double>? = null, yr: Pair<Double, Double>? = null): String {
    if (pts.size < 2) return ""
    val x0 = xr?.first ?: pts.minOf { it.first }; val x1 = xr?.second ?: pts.maxOf { it.first }
    val y0 = yr?.first ?: pts.minOf { it.second }; val y1 = yr?.second ?: pts.maxOf { it.second }
    val dx = max(x1 - x0, 1e-9); val dy = max(y1 - y0, 1e-9)
    val p = pts.joinToString(" ") { "${f((it.first - x0) / dx * w)},${f(h - (it.second - y0) / dy * h)}" }
    return if (fill) "<polygon points=\"0,$h $p $w,$h\" fill=\"$color\" fill-opacity=\"0.25\" stroke=\"$color\" stroke-width=\"2\"/>"
    else "<polyline points=\"$p\" fill=\"none\" stroke=\"$color\" stroke-width=\"2\"/>"
}

/** Kendi içinde tam (harici kaynaksız) HTML rapor. */
fun reportHtml(r: TripReport, title: String = "Sürüş Raporu"): String {
    val m = r.m
    val sb = StringBuilder()
    sb.append("<!doctype html><html lang=\"tr\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
    sb.append("<title>${esc(title)}</title><style>body{font-family:system-ui,sans-serif;background:#0f1419;color:#e6ebf2;margin:0;padding:16px;max-width:820px;margin:auto}")
    sb.append("h1{font-size:22px}h2{font-size:16px;margin-top:24px}.card{background:#1b232d;border-radius:12px;padding:12px;margin:8px 0}")
    sb.append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:8px}.k{color:#8b95a5;font-size:12px}.v{font-size:20px;font-weight:700}")
    sb.append(".warn{border-left:4px solid #f59e0b}.good{border-left:4px solid #22c55e}svg{width:100%;height:auto;background:#161c24;border-radius:8px}")
    sb.append("table{width:100%;border-collapse:collapse}td{padding:4px 6px;border-bottom:1px solid #263140;font-size:14px}a{color:#5393f7}</style></head><body>")
    sb.append("<h1>${esc(title)}</h1><div class=\"k\">${fmtDate(r.startTs)} - ${fmtDate(r.endTs)}</div>")
    sb.append("<div class=\"card\"><div class=\"k\">Sürüş puanı</div><div class=\"v\" style=\"font-size:40px\">${r.result.total ?: "-"}</div></div>")
    sb.append("<div class=\"grid\">")
    fun stat(k: String, v: String) { sb.append("<div class=\"card\"><div class=\"k\">$k</div><div class=\"v\">$v</div></div>") }
    stat("Mesafe", "${tr1(m.distanceKm, 2)} km"); stat("Süre", fmtDuration(m.durationS))
    stat("Ort. hız", "${tr1(m.avgSpeed, 0)} km/s"); stat("En yüksek hız", "${tr1(m.maxSpeed, 0)} km/s")
    if (m.hasAltitude) {
        stat("Toplam çıkış", "${tr1(m.ascent, 0)} m"); stat("Toplam iniş", "${tr1(m.descent, 0)} m")
        stat("En yüksek / düşük", "${tr1(m.maxAlt ?: 0.0, 0)} / ${tr1(m.minAlt ?: 0.0, 0)} m")
        stat("En dik yokuş / iniş", "%${tr1(m.maxUpGrade ?: 0.0, 1)} / %${tr1(m.maxDownGrade ?: 0.0, 1)}")
        stat("Yokuş / düz / iniş", "%${tr1(m.pctUp, 0)} / %${tr1(m.pctFlat, 0)} / %${tr1(m.pctDown, 0)}")
    }
    sb.append("</div><h2>Alt puanlar</h2><div class=\"card\"><table>")
    r.result.subs.forEach { (k, v) -> sb.append("<tr><td>${esc(k)}</td><td><b>$v</b></td></tr>") }
    sb.append("</table></div><h2>Koçluk</h2>")
    r.tips.forEach { sb.append("<div class=\"card ${it.kind}\">${esc(it.text)}</div>") }
    val ap = r.altProfile
    if (ap.size >= 2) {
        sb.append("<h2>Yükseklik profili (mesafeye göre)</h2><svg viewBox=\"0 0 600 200\">${polyline(ap, 600.0, 190.0, "#22c55e", true)}</svg>")
        sb.append("<div class=\"k\">0 - ${tr1(ap.last().first, 1)} km; ${tr1(ap.minOf { it.second }, 0)} - ${tr1(ap.maxOf { it.second }, 0)} m</div>")
    }
    val se = r.series
    if (se.size >= 2) {
        val tMax = se.last().t
        val sp = se.filter { it.speed != null }.map { it.t to it.speed!! }
        val rp = se.filter { it.rpm != null }.map { it.t to it.rpm!! }
        sb.append("<h2>Hız (mavi), devir (turuncu), vites (yeşil) - zamana göre</h2><svg viewBox=\"0 0 600 240\">")
        sb.append(polyline(sp, 600.0, 230.0, "#3b82f6", xr = 0.0 to tMax, yr = 0.0 to max(120.0, sp.maxOfOrNull { it.second } ?: 120.0)))
        sb.append(polyline(rp, 600.0, 230.0, "#f97316", xr = 0.0 to tMax, yr = 0.0 to max(4000.0, rp.maxOfOrNull { it.second } ?: 4000.0)))
        val gp = se.filter { it.gear != null }.map { it.t to it.gear!!.toDouble() }
        sb.append(polyline(gp, 600.0, 230.0, "#22c55e", xr = 0.0 to tMax, yr = 0.0 to max(6.0, (gp.maxOfOrNull { it.second } ?: 0.0) + 1)))
        for (e in r.result.events) {
            val x = e.t / max(tMax, 1.0) * 600
            sb.append("<line x1=\"${f(x)}\" y1=\"0\" x2=\"${f(x)}\" y2=\"230\" stroke=\"#ef4444\" stroke-opacity=\"0.5\"/>")
        }
        sb.append("</svg><div class=\"k\">Kırmızı çizgiler: olaylar</div>")
    }
    val rt = r.route
    if (rt.size >= 2) {
        val lat0 = rt.map { it.first }.average()
        val pts = rt.map { (it.second * cos(Math.toRadians(lat0))) to it.first }
        val xs = pts.map { it.first }; val ys = pts.map { it.second }
        val span = max(xs.max() - xs.min(), ys.max() - ys.min()).coerceAtLeast(1e-9)
        val sq = pts.map { ((it.first - xs.min()) / span * 280 + 10) to ((it.second - ys.min()) / span * 280 + 10) }
        val p = sq.joinToString(" ") { "${f(it.first)},${f(300 - it.second)}" }
        sb.append("<h2>Rota</h2><svg viewBox=\"0 0 300 300\"><polyline points=\"$p\" fill=\"none\" stroke=\"#f59e0b\" stroke-width=\"3\"/></svg>")
        r.osmLink()?.let { sb.append("<div><a href=\"$it\">OpenStreetMap'te başlangıcı göster</a></div>") }
    }
    sb.append("<h2>Olaylar</h2><div class=\"card\"><table>")
    if (r.result.events.isEmpty()) sb.append("<tr><td>Olay yok</td></tr>")
    r.result.events.forEach { sb.append("<tr><td>${fmtDuration(it.t)}</td><td>${esc(it.label)}</td><td>${esc(it.text)}</td></tr>") }
    sb.append("</table></div><div class=\"k\" style=\"margin-top:16px\">Rotiva - Android</div></body></html>")
    return sb.toString()
}
