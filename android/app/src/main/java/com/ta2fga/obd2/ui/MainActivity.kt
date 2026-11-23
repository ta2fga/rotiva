package com.ta2fga.obd2.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ta2fga.obd2.ble.ScanDevice
import com.ta2fga.obd2.core.fmtDate
import com.ta2fga.obd2.service.LinkState
import com.ta2fga.obd2.service.Engine
import com.ta2fga.obd2.service.Mode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(applicationContext)
        setContent {
            val theme by Engine.prefs.theme.collectAsStateWithLifecycle()
            AppTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) { Gate() }
                }
            }
        }
    }
}

fun hasPerm(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
fun locationGranted(ctx: Context) = hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
fun btGranted(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
    (hasPerm(ctx, Manifest.permission.BLUETOOTH_SCAN) && hasPerm(ctx, Manifest.permission.BLUETOOTH_CONNECT))

fun allRuntimePerms(): Array<String> {
    val l = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= 31) { l += Manifest.permission.BLUETOOTH_SCAN; l += Manifest.permission.BLUETOOTH_CONNECT }
    else { l += Manifest.permission.ACCESS_FINE_LOCATION }
    if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
    return l.distinct().toTypedArray()
}

@Composable
fun Gate() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(locationGranted(ctx)) }
    var skipped by rememberSaveable { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = locationGranted(ctx)
        denied = !granted
    }
    if (granted || skipped) {
        Root(granted)
    } else {
        PermissionScreen(denied, onRequest = { launcher.launch(allRuntimePerms()) }, onSkip = { skipped = true },
            onSettings = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) })
    }
}

@Composable
fun PermissionScreen(denied: Boolean, onRequest: () -> Unit, onSkip: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PSA OBD2 Tanı", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Uygulamanın çalışması için aşağıdaki izinlere ihtiyaç var. Verileriniz yalnızca telefonunuzda saklanır.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PermCard("Konum (hassas)", "Sürüş sırasında hız, rota, yükseklik ve eğimi GPS ile ölçmek ve ekran kapalıyken kaydı sürdürmek için gereklidir. Konumunuz hiçbir sunucuya gönderilmez.")
        PermCard("Yakındaki cihazlar (Bluetooth)", "ELM327 BLE OBD adaptörünüzü bulmak ve bağlanmak için gereklidir. Yalnızca OBD bağlantısı için kullanılır; Demo ve Telefon modu bu izin olmadan çalışır.")
        PermCard("Bildirimler", "Sürüş kaydı sürerken kalıcı bir bildirim gösterilir; uygulamaya dönmeyi ve sürüşü bitirmeyi kolaylaştırır. Android sizden bu izni ister.")
        PermCard("Ön plan servisi (konum)", "Kayıt, ekran kapalıyken de kesilmeden sürsün diye ön plan servisi olarak çalışır. Ayrı bir onay gerekmez, bildirimle görünür.")
        if (denied) {
            Text("Konum izni verilmedi. İzin olmadan sürüş kaydı ve GPS kullanılamaz. Ayarlardan izin verebilirsiniz.",
                color = LocalExtra.current.warn)
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Uygulama ayarlarını aç") }
        }
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("İzinleri ver") }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Şimdilik atla") }
    }
}

@Composable
fun PermCard(title: String, body: String) {
    AppCard(Modifier.fillMaxWidth()) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private class NavItem(val label: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(locationOk: Boolean) {
    val items = remember {
        listOf(
            NavItem("Panom", Icons.Filled.Home), NavItem("Değerler", Icons.AutoMirrored.Filled.List),
            NavItem("Arıza", Icons.Filled.Warning), NavItem("Sürüş", Icons.Filled.Place),
            NavItem("Geçmiş", Icons.Filled.DateRange), NavItem("Ayarlar", Icons.Filled.Settings),
        )
    }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var reportId by rememberSaveable { mutableStateOf<String?>(null) }
    var showConnect by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val msg by Engine.message.collectAsStateWithLifecycle()
    val finished by Engine.finishedTripId.collectAsStateWithLifecycle()
    val unfinished by Engine.unfinished.collectAsStateWithLifecycle()
    val conn by Engine.conn.collectAsStateWithLifecycle()

    val dash by Engine.prefs.dashboard.collectAsStateWithLifecycle()
    LaunchedEffect(page, dash) { Engine.wanted.value = if (page == 1) Engine.catalog.map { it.key }.toSet() else dash.toSet() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); Engine.message.value = null } }
    LaunchedEffect(finished) { finished?.let { reportId = it; page = 4; Engine.finishedTripId.value = null } }

    val rid = reportId
    if (rid != null) {
        BackHandler { reportId = null }
        ReportScreen(rid, onBack = { reportId = null })
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        topBar = { TopStatus(conn.mode, conn.state, conn.detail, conn.obdOk, conn.gpsOk) { showConnect = true } },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                items.forEachIndexed { i, it ->
                    NavigationBarItem(selected = page == i, onClick = { page = i },
                        icon = { Icon(it.icon, it.label) }, label = { Text(it.label, fontSize = 10.sp, maxLines = 1) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (page) {
                0 -> PagePanom()
                1 -> PageValues()
                2 -> PageDtc()
                3 -> PageDrive(onOpenConnect = { showConnect = true })
                4 -> PageHistory(onOpen = { reportId = it })
                else -> PageSettings()
            }
        }
    }

    if (showConnect) ConnectDialog(locationOk) { showConnect = false }

    unfinished?.let { u ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Yarım kalan sürüş") },
            text = { Text("${fmtDate(u.startTs)} tarihli, ${u.samples.size} örnekli bir sürüş kaydı tamamlanmamış. Ne yapmak istersiniz?") },
            confirmButton = { TextButton(onClick = { Engine.resumeUnfinished() }) { Text("Devam et") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { Engine.finishUnfinished() }) { Text("Bitir ve kaydet") }
                    TextButton(onClick = { Engine.discardUnfinished() }) { Text("Sil") }
                }
            },
        )
    }
}

@Composable
fun TopStatus(mode: Mode, state: LinkState, detail: String, obdOk: Boolean, gpsOk: Boolean, onClick: () -> Unit) {
    val ex = LocalExtra.current
    val color = when (state) {
        LinkState.CONNECTED -> ex.ok; LinkState.IDLE -> ex.muted; else -> ex.warn
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state == LinkState.CONNECTING || state == LinkState.RECONNECTING) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
        else Box(Modifier.size(12.dp).background(color, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(if (mode == Mode.NONE) "Bağlı değil" else mode.label, fontWeight = FontWeight.SemiBold)
            if (mode != Mode.NONE) Text(
                buildString { append(detail); append(if (detail.isEmpty()) "" else " · "); append(if (gpsOk) "GPS var" else "GPS yok") },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(if (mode == Mode.NONE) "Bağlan" else "Yönet", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = ex.border)
}

@Composable
fun ConnectDialog(locationOk: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val conn by Engine.conn.collectAsStateWithLifecycle()
    val scanner = Engine.scanner
    val devices by scanner.devices.collectAsStateWithLifecycle()
    val scanning by scanner.scanning.collectAsStateWithLifecycle()
    var btOk by remember { mutableStateOf(btGranted(ctx)) }
    var err by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { btOk = btGranted(ctx) }

    fun begin(mode: Mode, addr: String? = null, name: String? = null) {
        if (!locationGranted(ctx)) { err = "Konum izni gerekli (Ayarlar > Uygulamalar)."; return }
        scanner.stop()
        if (addr != null) { Engine.prefs.lastDevice = addr; Engine.prefs.lastDeviceName = name }
        if (Engine.begin(mode, addr)) onClose()
    }

    AlertDialog(
        onDismissRequest = { scanner.stop(); onClose() },
        title = { Text("Bağlantı") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (conn.mode != Mode.NONE) {
                    Text("Şu an: ${conn.mode.label} (${conn.detail})")
                    Button(onClick = { Engine.stop(); onClose() },
                        colors = ButtonDefaults.buttonColors(containerColor = LocalExtra.current.crit),
                        modifier = Modifier.fillMaxWidth()) { Text("Bağlantıyı kes") }
                    HorizontalDivider()
                }
                err?.let { Text(it, color = LocalExtra.current.crit) }
                Button(onClick = { begin(Mode.DEMO) }, modifier = Modifier.fillMaxWidth()) { Text("Demo (simülatör)") }
                OutlinedButton(onClick = { begin(Mode.PHONE) }, modifier = Modifier.fillMaxWidth()) { Text("Telefon modu (OBD yok, GPS hızı)") }
                HorizontalDivider()
                Text("OBD adaptörü (BLE ELM327)", fontWeight = FontWeight.SemiBold)
                if (!btOk) {
                    Text("Bluetooth taraması için 'Yakındaki cihazlar' izni gerekir.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { launcher.launch(allRuntimePerms()) }, modifier = Modifier.fillMaxWidth()) { Text("Bluetooth izni ver") }
                } else {
                    Engine.prefs.lastDevice?.let { addr ->
                        OutlinedButton(onClick = { begin(Mode.OBD, addr, Engine.prefs.lastDeviceName) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Son cihaz: ${Engine.prefs.lastDeviceName ?: addr}")
                        }
                    }
                    Button(onClick = {
                        if (!scanner.adapterOn()) err = "Bluetooth kapalı; açın." else { err = null; if (!scanner.start()) err = "Tarama başlatılamadı" }
                    }, enabled = !scanning, modifier = Modifier.fillMaxWidth()) { Text(if (scanning) "Taranıyor..." else "Cihazları tara") }
                    devices.forEach { d -> DeviceRow(d) { begin(Mode.OBD, d.address, d.name) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { scanner.stop(); onClose() }) { Text("Kapat") } },
    )
}

@Composable
fun DeviceRow(d: ScanDevice, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(d.name + if (d.obd) "  (OBD)" else "", fontWeight = if (d.obd) FontWeight.Bold else FontWeight.Normal)
            Text(d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
    }
}
