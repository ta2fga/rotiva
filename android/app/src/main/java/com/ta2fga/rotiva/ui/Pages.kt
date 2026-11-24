package com.ta2fga.rotiva.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ta2fga.rotiva.core.*
import com.ta2fga.rotiva.data.SpeedUnit
import com.ta2fga.rotiva.data.TempUnit
import com.ta2fga.rotiva.service.Engine
import com.ta2fga.rotiva.service.Mode
import kotlinx.coroutines.launch
import org.json.JSONObject

val ALL_CATEGORIES = CATEGORIES + "Telefon"

// ---------------------------------------------------------------------------------------------- Panom
@Composable
fun ValueCard(item: Item, v: Double?, su: SpeedUnit, tu: TempUnit, big: Boolean = true, stale: Boolean = false,
              onClick: (() -> Unit)? = null) {
    val (txt, unit) = formatValue(item, v, su, tu)
    val col = statusColor(statusOf(item, v)).let { if (stale) it.copy(alpha = 0.45f) else it }   // gecikmiş: soluk
    AppCard(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Text(item.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(txt, fontSize = if (big) 40.sp else 28.sp, fontWeight = FontWeight.Bold, color = col)
            Spacer(Modifier.size(6.dp))
            Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
fun PagePanom() {
    val p = Engine.prefs
    val dash by p.dashboard.collectAsStateWithLifecycle()
    val values by Engine.values.collectAsStateWithLifecycle()
    val stale by Engine.stale.collectAsStateWithLifecycle()
    val su by p.speedUnit.collectAsStateWithLifecycle()
    val tu by p.tempUnit.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val byKey = remember { Engine.catalog.associateBy { it.key } }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Panom", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { picking = true }) { Text("Değer seç") }
        }
        if (dash.isEmpty()) Text("Panoya değer eklemek için 'Değer seç' veya Değerler sayfasındaki yıldıza dokunun.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyVerticalGrid(GridCells.Adaptive(160.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            gridItems(dash.mapNotNull { byKey[it] }, key = { it.key }) { it2 -> ValueCard(it2, values[it2.key], su, tu, stale = it2.key in stale) }
        }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Panoda gösterilecek değerler") },
            text = {
                LazyColumn {
                    items(Engine.catalog) { it2 ->
                        Row(Modifier.fillMaxWidth().clickable { p.togglePin(it2.key) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = it2.key in dash, onCheckedChange = { p.togglePin(it2.key) })
                            Column { Text(it2.name); Text(it2.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Tamam") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------- Tüm Değerler
@Composable
fun PageValues() {
    val p = Engine.prefs
    val dash by p.dashboard.collectAsStateWithLifecycle()
    val values by Engine.values.collectAsStateWithLifecycle()
    val su by p.speedUnit.collectAsStateWithLifecycle()
    val tu by p.tempUnit.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf("") }
    val ex = LocalExtra.current
    val items = remember(q) {
        Engine.catalog.filter { q.isBlank() || it.name.contains(q, true) || it.category.contains(q, true) || it.key.contains(q, true) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), singleLine = true,
            placeholder = { Text("Ara...") }, leadingIcon = { Icon(Icons.Filled.Search, null) })
        LazyColumn(Modifier.fillMaxSize()) {
            ALL_CATEGORIES.forEach { cat ->
                val list = items.filter { it.category == cat }
                if (list.isNotEmpty()) {
                    item(key = "h_$cat") { SectionTitle(cat) }
                    items(list, key = { it.key }) { it2 ->
                        val (txt, unit) = formatValue(it2, values[it2.key], su, tu)
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(it2.name) }
                            Text("$txt $unit", fontWeight = FontWeight.Bold, color = statusColor(statusOf(it2, values[it2.key])))
                            IconButton(onClick = { p.togglePin(it2.key) }) {
                                Icon(Icons.Filled.Star, "Panoya sabitle", tint = if (it2.key in dash) MaterialTheme.colorScheme.primary else ex.border)
                            }
                        }
                        HorizontalDivider(color = ex.border.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- Arıza kodları
object DtcDb {
    private var map: Map<String, String>? = null
    fun describe(ctx: Context, code: String): String {
        val m = map ?: load(ctx).also { map = it }
        return m[code] ?: "Açıklama bulunamadı (üretici özel kodu olabilir)"
    }
    private fun load(ctx: Context): Map<String, String> {
        val out = HashMap<String, String>()
        fun read(name: String, key: String?) = runCatching {
            val j = JSONObject(ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() })
            val o = if (key != null) j.getJSONObject(key) else j
            o.keys().forEach { k -> out.putIfAbsent(k, o.getString(k)) }
        }
        read("dtc_generic.json", null)
        read("psa_data.json", "dtcs")
        return out
    }
}

@Composable
fun PageDtc() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val conn by Engine.conn.collectAsStateWithLifecycle()
    var result by remember { mutableStateOf<Map<Int, List<String>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val canUse = (conn.mode == Mode.OBD || conn.mode == Mode.DEMO) && conn.obdOk
    val ex = LocalExtra.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Arıza Kodları", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Standart OBD2 servisleri: 03 (kayıtlı), 07 (bekleyen), 0A (kalıcı).", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!canUse) Text("Arıza kodları için OBD adaptörüne (veya Demo'ya) bağlanın.", color = ex.warn)
        RowSpaced {
            Button(onClick = {
                busy = true; err = null
                scope.launch { try { result = Engine.readDtcs() } catch (e: Exception) { err = e.message } finally { busy = false } }
            }, enabled = canUse && !busy, modifier = Modifier.weight(1f)) { Text(if (busy) "Okunuyor..." else "Kodları oku") }
            Button(onClick = { confirm = true }, enabled = canUse && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = ex.crit), modifier = Modifier.weight(1f)) { Text("Kodları sil") }
        }
        err?.let { Text(it, color = ex.crit) }
        result?.let { r ->
            listOf(0x03 to "Kayıtlı kodlar (03)", 0x07 to "Bekleyen kodlar (07)", 0x0A to "Kalıcı kodlar (0A)").forEach { (m, title) ->
                SectionTitle(title)
                val l = r[m].orEmpty()
                if (l.isEmpty()) Text("Kod yok", color = ex.ok)
                l.forEach { c ->
                    AppCard(Modifier.fillMaxWidth()) {
                        Text(c, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = ex.warn)
                        Text(DtcDb.describe(ctx, c))
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Arıza kodları silinsin mi?") },
            text = { Text("Tüm kayıtlı arıza kodları ve dondurulmuş veriler silinir, hazır durumu (readiness) sıfırlanır. Arıza sürüyorsa kodlar geri gelir. Bu işlem geri alınamaz.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false; busy = true
                    scope.launch {
                        try { val ok = Engine.clearDtcs(); Engine.message.value = if (ok) "Arıza kodları silindi" else "ECU silmeyi onaylamadı"; result = Engine.readDtcs() }
                        catch (e: Exception) { err = e.message } finally { busy = false }
                    }
                }) { Text("Sil", color = ex.crit) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Vazgeç") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------- Sürüş
@Composable
fun PageDrive(onOpenConnect: () -> Unit) {
    val live by Engine.live.collectAsStateWithLifecycle()
    val trip by Engine.trip.collectAsStateWithLifecycle()
    val conn by Engine.conn.collectAsStateWithLifecycle()
    val su by Engine.prefs.speedUnit.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val ex = LocalExtra.current

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // büyük hız
        AppCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                Text(speedText(live.speed, su), fontSize = 120.sp, fontWeight = FontWeight.Bold, lineHeight = 120.sp)
                Text(su.label, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, bottom = 18.dp))
            }
        }
        RowSpaced {
            BigStat("Devir", live.rpm?.let { "%d".format(Math.round(it)) } ?: "—", "d/dk", Modifier.weight(1f),
                if ((live.rpm ?: 0.0) > 3500) ex.crit else MaterialTheme.colorScheme.onSurface)
            BigStat("Vites", live.gearLabel, "", Modifier.weight(1f))   // 1..n (ayar: vites sayısı), N (boşta/debriyaj), — (bilinmiyor)
            BigStat("Yükseklik", live.alt?.let { "%d".format(Math.round(it)) } ?: "—", "m", Modifier.weight(1f))
        }
        RowSpaced {
            BigStat("Eğim", live.grade?.let { tr1(it, 1) } ?: "—", "%", Modifier.weight(1f))
            BigStat("Boyuna", live.gLong?.let { tr1(it, 2) } ?: "—", "g", Modifier.weight(1f))
            BigStat("Yanal", live.gLat?.let { tr1(it, 2) } ?: "—", "g",
                Modifier.weight(1f), if (Math.abs(live.gLat ?: 0.0) > 0.35) ex.crit else MaterialTheme.colorScheme.onSurface)
        }
        if (conn.mode != Mode.NONE) Text(
            buildString {
                append(if (live.hasAccel) (if (live.calibrated) "İvmeölçer kalibre" else "İvmeölçer kalibre ediliyor (düz yolda hızlanın/frenleyin)") else "İvmeölçer yok")
                append(" · "); append(if (live.hasBaro) "Barometre var" else "Barometre yok")
                live.gpsAcc?.let { append(" · GPS ±${it.toInt()} m") }
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f))
        AppCard(Modifier.fillMaxWidth()) {
            if (trip.active) {
                Text(if (trip.paused) "Sürüş duraklatıldı (veri bekleniyor)" else "Sürüş kaydediliyor", fontWeight = FontWeight.SemiBold, color = if (trip.paused) ex.warn else ex.ok)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    KeyValue("Mesafe", "${tr1(trip.distanceKm)} km")
                    KeyValue("Süre", "${(trip.durationS / 60).toInt()} dk")
                    KeyValue("Çıkış", "${trip.ascent.toInt()} m")
                    KeyValue("İniş", "${trip.descent.toInt()} m")
                    trip.score?.let { KeyValue("Puan", "$it") }
                }
            } else {
                Text(if (conn.mode == Mode.NONE) "Başlamak için bir kaynağa bağlanın." else if (Engine.prefs.autoStart.value) "Hız 5 km/s'yi aşınca sürüş otomatik başlar." else "Sürüşü başlatmak için düğmeye basın.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (conn.mode == Mode.NONE) {
            Button(onClick = onOpenConnect, Modifier.fillMaxWidth().height(64.dp)) { Text("Bağlan", fontSize = 20.sp) }
        } else if (trip.active) {
            Button(onClick = { confirm = true }, Modifier.fillMaxWidth().height(72.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ex.crit, contentColor = Color.White)) { Text("Sürüşü bitir", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        } else {
            Button(onClick = { Engine.startTrip() }, Modifier.fillMaxWidth().height(72.dp)) { Text("Sürüşü başlat", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Sürüş bitirilsin mi?") },
            text = { Text("Kayıt durdurulur, rapor oluşturulur. Duruşlar (ışık, trafik) bu sürüşün parçasıdır; yalnızca yolculuk gerçekten bittiyse bitirin.") },
            confirmButton = { TextButton(onClick = { confirm = false; Engine.finishTrip() }) { Text("Bitir") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Devam et") } },
        )
    }
}

@Composable
fun BigStat(label: String, value: String, unit: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    AppCard(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = color)
            if (unit.isNotEmpty()) Text(" $unit", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------- Geçmiş
@Composable
fun PageHistory(onOpen: (String) -> Unit) {
    var trips by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var del by remember { mutableStateOf<String?>(null) }
    val trip by Engine.trip.collectAsStateWithLifecycle()
    LaunchedEffect(trip.active, del) { trips = Engine.store.list() }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Geçmiş", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        if (trips.isEmpty()) Text("Henüz kayıtlı sürüş yok.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(trips, key = { it.optString("id") }) { j ->
                val m = j.optJSONObject("metrics")
                val score = if (j.isNull("total")) null else j.optInt("total")
                AppCard(Modifier.fillMaxWidth().clickable { onOpen(j.getString("id")) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fmtDate(j.optDouble("start_ts")), fontWeight = FontWeight.SemiBold)
                            Text("${tr1(m?.optDouble("distance_km") ?: 0.0, 1)} km · ${fmtDuration(m?.optDouble("duration_s") ?: 0.0)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (m?.optBoolean("has_altitude") == true)
                                Text("↑ ${tr1(m.optDouble("ascent"), 0)} m   ↓ ${tr1(m.optDouble("descent"), 0)} m", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(score?.toString() ?: "—", fontSize = 34.sp, fontWeight = FontWeight.Bold,
                            color = when { score == null -> LocalExtra.current.muted; score >= 80 -> LocalExtra.current.ok; score >= 60 -> LocalExtra.current.warn; else -> LocalExtra.current.crit })
                        TextButton(onClick = { del = j.getString("id") }) { Text("Sil") }
                    }
                }
            }
        }
    }
    del?.let { id ->
        AlertDialog(
            onDismissRequest = { del = null },
            title = { Text("Sürüş silinsin mi?") },
            text = { Text("Bu sürüşün kaydı ve raporu kalıcı olarak silinir.") },
            confirmButton = { TextButton(onClick = { Engine.store.delete(id); del = null }) { Text("Sil") } },
            dismissButton = { TextButton(onClick = { del = null }) { Text("Vazgeç") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------- Ayarlar
@Composable
fun PageSettings() {
    val p = Engine.prefs
    val theme by p.theme.collectAsStateWithLifecycle()
    val auto by p.autoStart.collectAsStateWithLifecycle()
    val su by p.speedUnit.collectAsStateWithLifecycle()
    val tu by p.tempUnit.collectAsStateWithLifecycle()
    val gearCount by p.gearCount.collectAsStateWithLifecycle()
    var resetGear by remember { mutableStateOf(false) }
    var askGears by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ayarlar", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        SectionTitle("Tema")
        PALETTES.forEach { pal ->
            Row(Modifier.fillMaxWidth().clickable { p.setTheme(pal.name) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = theme == pal.name, onClick = { p.setTheme(pal.name) })
                Text(pal.name, modifier = Modifier.weight(1f))
                pal.series.take(4).forEach { c -> Box(Modifier.padding(2.dp).size(18.dp).background(Color(c), androidx.compose.foundation.shape.CircleShape)) }
            }
        }
        SectionTitle("Sürüş kaydı")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Otomatik başlat")
                Text("Hız 5 km/s'yi aşınca sürüşü başlatır. Sürüş yalnızca 'Sürüşü bitir' ile sona erer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(auto, { p.setAutoStart(it) })
        }
        SectionTitle("Birimler")
        Text("Hız", color = MaterialTheme.colorScheme.onSurfaceVariant)
        RowSpaced { SpeedUnit.values().forEach { u -> Chip(u.label, su == u, Modifier.weight(1f)) { p.setSpeedUnit(u) } } }
        Text("Sıcaklık", color = MaterialTheme.colorScheme.onSurfaceVariant)
        RowSpaced { TempUnit.values().forEach { u -> Chip(u.label, tu == u, Modifier.weight(1f)) { p.setTempUnit(u) } } }
        Text("Yakıt ray basıncı her zaman bar olarak gösterilir.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("Vites tahmini")
        Text("Vites sayısı (ileri)", color = MaterialTheme.colorScheme.onSurfaceVariant)
        RowSpaced { GEAR_COUNTS.forEach { k -> Chip("$k", gearCount == k, Modifier.weight(1f)) { if (k != gearCount) askGears = k } } }
        Text("Değiştirince vites öğrenmesi sıfırlanır.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Oranlar (km/s / 1000 d/dk): " + Engine.gearRatios().joinToString("  ") { tr1(it, 1) }, style = MaterialTheme.typography.bodyMedium)
        Text("Öğrenme sayacı: " + Engine.gearCounts().joinToString(" / "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { resetGear = true }) { Text("Vites öğrenmesini sıfırla") }
        SectionTitle("Hakkında")
        Text("Rotiva - Android. Masaüstü uygulamanın mantığı (ELM327, vites tahmini, puanlama, koçluk) taşınmıştır.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (resetGear) AlertDialog(
        onDismissRequest = { resetGear = false }, title = { Text("Sıfırlansın mı?") },
        text = { Text("Öğrenilmiş vites oranları varsayılana döner.") },
        confirmButton = { TextButton(onClick = { Engine.resetGear(); resetGear = false }) { Text("Sıfırla") } },
        dismissButton = { TextButton(onClick = { resetGear = false }) { Text("Vazgeç") } })
    askGears?.let { k ->
        AlertDialog(
            onDismissRequest = { askGears = null }, title = { Text("Vites sayısı: $k") },
            text = { Text("Vites sayısı $k olarak değiştirilecek. Öğrenilmiş vites oranları silinir ve öğrenme baştan başlar.") },
            confirmButton = { TextButton(onClick = { Engine.setGearCount(k); askGears = null }) { Text("Değiştir") } },
            dismissButton = { TextButton(onClick = { askGears = null }) { Text("Vazgeç") } })
    }
}

@Composable
fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}
