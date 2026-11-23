package com.ta2fga.obd2.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ta2fga.obd2.core.*
import com.ta2fga.obd2.service.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

@Composable
fun ReportScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var report by remember(id) { mutableStateOf<TripReport?>(null) }
    LaunchedEffect(id) {
        report = withContext(Dispatchers.Default) { TripReport.build(Engine.store.loadSamples(id)) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Geri") }
            Text("Sürüş Raporu", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { report?.let { shareReport(ctx, it, id) } }, enabled = report != null) { Text("Paylaş (HTML)") }
        }
        val r = report
        if (r == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else ReportBody(r, ctx)
    }
}

fun shareReport(ctx: Context, r: TripReport, id: String) {
    val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
    val f = File(dir, "PSA-OBD2-Rapor-$id.html")
    f.writeText(reportHtml(r, "Sürüş Raporu " + fmtDate(r.startTs)), Charsets.UTF_8)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/html"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, "Sürüş Raporu")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Raporu paylaş").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun ReportBody(r: TripReport, ctx: Context) {
    val m = r.m
    val ex = LocalExtra.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${fmtDate(r.startTs)} - ${fmtDate(r.endTs)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        AppCard(Modifier.fillMaxWidth()) {
            Text("Sürüş puanı", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val t = r.result.total
            Text(t?.toString() ?: "—", fontSize = 64.sp, fontWeight = FontWeight.Bold,
                color = when { t == null -> ex.muted; t >= 80 -> ex.ok; t >= 60 -> ex.warn; else -> ex.crit })
            if (r.result.short) Text("Sürüş kısa olduğu için puan yaklaşıktır.", color = ex.warn, style = MaterialTheme.typography.bodySmall)
            r.result.subs.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { v / 100f }, modifier = Modifier.weight(1f).height(8.dp),
                        color = if (v >= 80) ex.ok else if (v >= 60) ex.warn else ex.crit, trackColor = ex.border)
                    Text("$v", Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                }
            }
        }
        StatGrid(listOf(
            "Mesafe" to "${tr1(m.distanceKm, 2)} km", "Süre" to fmtDuration(m.durationS),
            "Ort. hız" to "${tr1(m.avgSpeed, 0)} km/s", "En yüksek hız" to "${tr1(m.maxSpeed, 0)} km/s",
        ))
        if (m.hasAltitude) {
            StatGrid(listOf(
                "Toplam çıkış" to "${tr1(m.ascent, 0)} m", "Toplam iniş" to "${tr1(m.descent, 0)} m",
                "En yüksek nokta" to "${tr1(m.maxAlt ?: 0.0, 0)} m", "En düşük nokta" to "${tr1(m.minAlt ?: 0.0, 0)} m",
                "En dik yokuş" to "%${tr1(m.maxUpGrade ?: 0.0, 1)}", "En dik iniş" to "%${tr1(m.maxDownGrade ?: 0.0, 1)}",
                "Yokuş / düz / iniş" to "%${tr1(m.pctUp, 0)} / %${tr1(m.pctFlat, 0)} / %${tr1(m.pctDown, 0)}",
            ))
        }

        SectionTitle("Koçluk")
        r.tips.forEach { t ->
            AppCard(Modifier.fillMaxWidth()) { Text(t.text, color = if (t.kind == "good") ex.ok else MaterialTheme.colorScheme.onSurface) }
        }

        if (r.altProfile.size >= 2) {
            SectionTitle("Yükseklik profili (mesafeye göre)")
            AppCard(Modifier.fillMaxWidth()) {
                AltitudeChart(r.altProfile, ex.series[1], ex.border, Modifier.fillMaxWidth().height(180.dp))
                val a = r.altProfile
                Text("0 - ${tr1(a.last().first, 1)} km · ${tr1(a.minOf { it.second }, 0)} - ${tr1(a.maxOf { it.second }, 0)} m",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (r.series.size >= 2) {
            SectionTitle("Hız, devir ve vites (zamana göre)")
            AppCard(Modifier.fillMaxWidth()) {
                SpeedRpmChart(r, ex.series[0], ex.series[2], ex.series[1], ex.crit, ex.border, Modifier.fillMaxWidth().height(220.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Legend("Hız", ex.series[0]); Legend("Devir", ex.series[2]); Legend("Vites", ex.series[1]); Legend("Olay", ex.crit)
                }
            }
        }
        if (r.route.size >= 2) {
            SectionTitle("Rota")
            AppCard(Modifier.fillMaxWidth()) {
                RouteChart(r.route, ex.series[0], ex.ok, ex.crit, Modifier.fillMaxWidth().height(260.dp))
                r.osmLink()?.let { link ->
                    TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                        Text("Başlangıcı OpenStreetMap'te göster")
                    }
                }
            }
        }
        SectionTitle("Olaylar")
        if (r.result.events.isEmpty()) Text("Olay yok.", color = ex.ok)
        r.result.events.forEach { e ->
            AppCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(e.label, fontWeight = FontWeight.SemiBold, color = ex.warn)
                    Text(fmtDuration(e.t), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(e.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Box(Modifier.height(24.dp))
    }
}

@Composable
fun StatGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { row ->
            RowSpaced { row.forEach { (k, v) -> AppCard(Modifier.weight(1f)) { KeyValue(k, v) } }
                if (row.size == 1) Box(Modifier.weight(1f)) }
        }
    }
}

@Composable
fun Legend(t: String, c: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).padding(0.dp).let { it }) { Canvas(Modifier.size(10.dp)) { drawCircle(c) } }
        Text(" $t", style = MaterialTheme.typography.bodySmall)
    }
}

private fun DrawScope.grid(color: Color, rows: Int = 4) {
    for (i in 0..rows) { val y = size.height * i / rows; drawLine(color, Offset(0f, y), Offset(size.width, y), 1f) }
}

@Composable
fun AltitudeChart(pts: List<Pair<Double, Double>>, line: Color, grid: Color, modifier: Modifier) {
    Canvas(modifier) {
        grid(grid.copy(alpha = 0.5f))
        val x0 = pts.first().first; val x1 = pts.last().first
        val lo = pts.minOf { it.second }; val hi = pts.maxOf { it.second }
        val dy = max(hi - lo, 5.0); val dx = max(x1 - x0, 1e-6)
        val path = Path(); val fill = Path()
        pts.forEachIndexed { i, p ->
            val x = ((p.first - x0) / dx * size.width).toFloat()
            val y = (size.height - (p.second - lo) / dy * size.height * 0.9f - size.height * 0.05f).toFloat()
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, size.height); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo(size.width, size.height); fill.close()
        drawPath(fill, line.copy(alpha = 0.22f))
        drawPath(path, line, style = Stroke(width = 4f))
    }
}

@Composable
fun SpeedRpmChart(r: TripReport, speedC: Color, rpmC: Color, gearC: Color, evC: Color, grid: Color, modifier: Modifier) {
    Canvas(modifier) {
        grid(grid.copy(alpha = 0.5f))
        val se = r.series
        val tMax = max(se.last().t, 1.0)
        val sMax = max(120.0, se.mapNotNull { it.speed }.maxOrNull() ?: 120.0)
        val rMax = max(4000.0, se.mapNotNull { it.rpm }.maxOrNull() ?: 4000.0)
        fun line(sel: (TripReport.TsPoint) -> Double?, maxV: Double, c: Color, w: Float, step: Boolean = false) {
            val p = Path(); var started = false; var lastY = 0f
            for (pt in se) {
                val v = sel(pt)
                if (v == null) { started = false; continue }
                val x = (pt.t / tMax * size.width).toFloat()
                val y = (size.height - v / maxV * size.height).toFloat()
                if (!started) { p.moveTo(x, y); started = true } else { if (step) p.lineTo(x, lastY); p.lineTo(x, y) }
                lastY = y
            }
            drawPath(p, c, style = Stroke(width = w))
        }
        line({ it.rpm }, rMax, rpmC.copy(alpha = 0.8f), 3f)
        line({ it.speed }, sMax, speedC, 4f)
        line({ it.gear?.toDouble() }, 6.0, gearC, 3f, step = true)
        r.result.events.forEach { e ->
            val x = (e.t / tMax * size.width).toFloat()
            drawLine(evC.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, size.height), 2f)
            drawCircle(evC, 6f, Offset(x, 8f))
        }
    }
}

@Composable
fun RouteChart(route: List<Pair<Double, Double>>, line: Color, start: Color, end: Color, modifier: Modifier) {
    Canvas(modifier) {
        val lat0 = route.map { it.first }.average()
        val pts = route.map { (it.second * cos(Math.toRadians(lat0))) to it.first }
        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        val span = max(max(maxX - minX, maxY - minY), 1e-9)
        val scale = min(size.width, size.height) * 0.9 / span
        val ox = (size.width - (maxX - minX) * scale) / 2; val oy = (size.height - (maxY - minY) * scale) / 2
        fun map(p: Pair<Double, Double>) = Offset((ox + (p.first - minX) * scale).toFloat(), (size.height - (oy + (p.second - minY) * scale)).toFloat())
        val path = Path()
        pts.forEachIndexed { i, p -> val o = map(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, line, style = Stroke(width = 5f))
        drawCircle(start, 12f, map(pts.first())); drawCircle(end, 12f, map(pts.last()))
    }
}
