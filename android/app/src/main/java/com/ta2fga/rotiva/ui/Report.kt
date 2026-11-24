package com.ta2fga.rotiva.ui

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.ta2fga.rotiva.core.*
import com.ta2fga.rotiva.service.Engine
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
    val f = File(dir, "Rotiva-Rapor-$id.html")
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
    var cursor by remember { mutableStateOf<Int?>(null) }
    var routeMode by remember { mutableStateOf(RouteColor.SPEED) }
    var fullscreen by remember { mutableStateOf(false) }
    var showEvents by rememberSaveable { mutableStateOf(true) }
    if (fullscreen) FullscreenMap(r, routeMode, { routeMode = it }, showEvents, { showEvents = it }, cursor) { fullscreen = false }
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

        if (r.track.count { it.alt != null } >= 2) {
            SectionTitle("Yükseklik profili (mesafeye göre)")
            AppCard(Modifier.fillMaxWidth()) {
                ProfileChart(r, ChartKind.ALT, cursor, { cursor = it }, ex.series[1], ex.series[2], ex.series[1], ex.crit, ex.border,
                    MaterialTheme.colorScheme.onSurfaceVariant, Modifier.fillMaxWidth().height(200.dp))
                Text("Grafiğe dokunun veya sürükleyin: imleç haritada da görünür.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (r.track.size >= 2) {
            SectionTitle("Hız, devir ve vites (zamana göre)")
            AppCard(Modifier.fillMaxWidth()) {
                ProfileChart(r, ChartKind.SPEED, cursor, { cursor = it }, ex.series[0], ex.series[2], ex.series[1], ex.crit, ex.border,
                    MaterialTheme.colorScheme.onSurfaceVariant, Modifier.fillMaxWidth().height(230.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Legend("Hız", ex.series[0]); Legend("Devir (0-4000+)", ex.series[2]); Legend("Vites (0-6)", ex.series[1]); Legend("Olay", ex.crit)
                }
            }
        }
        if (r.track.count { it.lat != null } >= 2) {
            SectionTitle("Rota (OpenStreetMap)")
            AppCard(Modifier.fillMaxWidth()) {
                RouteControls(routeMode, { routeMode = it }, showEvents, { showEvents = it }, onFullscreen = { fullscreen = true })
                RouteMap(r, routeMode, cursor, Modifier.fillMaxWidth().height(300.dp), showEvents)
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


@Composable
fun RouteControls(
    mode: RouteColor, onMode: (RouteColor) -> Unit,
    showEvents: Boolean, onShowEvents: (Boolean) -> Unit,
    onFullscreen: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        RouteColor.values().forEach { m ->
            if (m == mode) Button(onClick = { onMode(m) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text(m.label) }
            else OutlinedButton(onClick = { onMode(m) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) { Text(m.label) }
        }
        Box(Modifier.weight(1f))
        if (onFullscreen != null) TextButton(onClick = onFullscreen) { Text("Tam ekran") }
    }
    val lg = legendFor(mode)
    // "Olaylar" anahtarı + lejant; dar ekranda yatay kaydırılır
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = showEvents, onClick = { onShowEvents(!showEvents) },
            label = { Text("Olaylar") },
            leadingIcon = if (showEvents) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
            } else null,
        )
        lg.forEach { (t, c) -> RouteLegend(t, Color(c)) }
    }
}

/** Rota lejantı: açık renkler (ör. "düz" beyazı) seçilebilsin diye haritadaki gibi koyu konturlu. */
@Composable
private fun RouteLegend(t: String, c: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(12.dp)) {
            drawCircle(Color(CASING_COLOR))
            drawCircle(c, radius = size.minDimension / 2 - 2.dp.toPx())
        }
        Text(" $t", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun FullscreenMap(
    r: TripReport, mode: RouteColor, onMode: (RouteColor) -> Unit,
    showEvents: Boolean, onShowEvents: (Boolean) -> Unit,
    cursor: Int?, onClose: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(top = 28.dp, bottom = 12.dp).padding(horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Column { RouteControls(mode, onMode, showEvents, onShowEvents, null) } }
                    Button(onClick = onClose) { Text("Kapat") }
                }
                RouteMap(r, mode, cursor, Modifier.fillMaxWidth().weight(1f), showEvents)
            }
        }
    }
}
