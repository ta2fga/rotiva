package com.ta2fga.obd2.ui

import android.content.Context
import android.graphics.Color as AColor
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ta2fga.obd2.BuildConfig
import com.ta2fga.obd2.core.TripReport
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

enum class RouteColor(val label: String) { PLAIN("Düz"), SPEED("Hız"), GRADE("Eğim") }

/** OSM kullanım politikası: kullanıcı aracısı = uygulama kimliği, disk önbelleği açık. */
fun initOsm(ctx: Context) {
    val c = Configuration.getInstance()
    c.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    c.userAgentValue = BuildConfig.APPLICATION_ID
    val base = File(ctx.cacheDir, "osmdroid").apply { mkdirs() }
    c.osmdroidBasePath = base
    c.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
    c.tileFileSystemCacheMaxBytes = 100L * 1024 * 1024
    c.tileFileSystemCacheTrimBytes = 80L * 1024 * 1024
}

private val SPEED_COLORS = intArrayOf(0xFF22C55E.toInt(), 0xFF84CC16.toInt(), 0xFFEAB308.toInt(), 0xFFF97316.toInt(), 0xFFEF4444.toInt())
private val GRADE_COLORS = intArrayOf(0xFF2563EB.toInt(), 0xFF60A5FA.toInt(), 0xFF22C55E.toInt(), 0xFFF97316.toInt(), 0xFFEF4444.toInt())

fun speedBucket(v: Double?): Int = when { v == null -> 0; v < 30 -> 0; v < 60 -> 1; v < 90 -> 2; v < 120 -> 3; else -> 4 }
fun gradeBucket(g: Double?): Int = when { g == null -> 2; g < -6 -> 0; g < -2 -> 1; g <= 2 -> 2; g <= 6 -> 3; else -> 4 }

fun legendFor(mode: RouteColor): List<Pair<String, Int>> = when (mode) {
    RouteColor.PLAIN -> emptyList()
    RouteColor.SPEED -> listOf("<30" to 0, "30-60" to 1, "60-90" to 2, "90-120" to 3, ">120 km/s" to 4)
        .map { it.first to SPEED_COLORS[it.second] }
    RouteColor.GRADE -> listOf("<-6%" to 0, "-6..-2" to 1, "düz" to 2, "2..6" to 3, ">6% yokuş" to 4)
        .map { it.first to GRADE_COLORS[it.second] }
}

private fun dot(color: Int, sizePx: Int, stroke: Int = AColor.WHITE) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL; setColor(color); setStroke(4, stroke); setSize(sizePx, sizePx)
}

private val EVENT_STYLE = mapOf(
    "harsh_brake" to ("Sert fren" to 0xFFEF4444.toInt()),
    "harsh_accel" to ("Sert hızlanma" to 0xFFF97316.toInt()),
    "harsh_corner" to ("Sert viraj" to 0xFFA855F7.toInt()),
    "lugging" to ("Motoru zorlama" to 0xFFEAB308.toInt()),
    "lug_uphill" to ("Motoru zorlama" to 0xFFEAB308.toInt()),
)

private fun rebuild(map: MapView, r: TripReport, mode: RouteColor, cursorMarker: Marker, first: Boolean) {
    map.overlays.clear()
    val pts = r.track.filter { it.lat != null && it.lon != null }
    if (pts.size < 2) return
    // rota: aynı renk kümesindeki ardışık parçalar tek çizgi
    fun colorOf(i: Int): Int = when (mode) {
        RouteColor.PLAIN -> 0xFF3B82F6.toInt()
        RouteColor.SPEED -> SPEED_COLORS[speedBucket(pts[i].speed)]
        RouteColor.GRADE -> GRADE_COLORS[gradeBucket(pts[i].grade)]
    }
    var runStart = 0
    for (i in 1..pts.size) {
        if (i == pts.size || colorOf(i) != colorOf(runStart)) {
            val end = if (i == pts.size) pts.size - 1 else i
            val line = Polyline().apply {
                setPoints((runStart..end).map { GeoPoint(pts[it].lat!!, pts[it].lon!!) })
                outlinePaint.color = colorOf(runStart); outlinePaint.strokeWidth = 10f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                setOnClickListener { _, _, _ -> false }
            }
            map.overlays.add(line)
            runStart = end
            if (i == pts.size) break
        }
    }
    fun marker(p: GeoPoint, color: Int, size: Int, title: String?, snippet: String?) = Marker(map).apply {
        position = p; icon = dot(color, size); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        this.title = title; this.snippet = snippet
    }
    for (ep in r.eventPositions) {
        val st = EVENT_STYLE[ep.event.type] ?: continue
        map.overlays.add(marker(GeoPoint(ep.lat, ep.lon), st.second, 30, st.first, "${ep.event.text} · ${com.ta2fga.obd2.core.fmtDuration(ep.event.t)}"))
    }
    map.overlays.add(marker(GeoPoint(pts.first().lat!!, pts.first().lon!!), 0xFF22C55E.toInt(), 44, "Başlangıç", null))
    map.overlays.add(marker(GeoPoint(pts.last().lat!!, pts.last().lon!!), 0xFFEF4444.toInt(), 44, "Bitiş", null))
    map.overlays.add(cursorMarker)
    if (first) {
        val geo = pts.map { GeoPoint(it.lat!!, it.lon!!) }
        var box = BoundingBox.fromGeoPoints(geo)
        if (box.latNorth - box.latSouth < 1e-4 || box.lonEast - box.lonWest < 1e-4) {
            box = BoundingBox(box.latNorth + 0.002, box.lonEast + 0.002, box.latSouth - 0.002, box.lonWest - 0.002)
        }
        val z = { map.zoomToBoundingBox(box, false, 80) }
        if (map.width > 0) z() else map.addOnFirstLayoutListener { _, _, _, _, _ -> z() }
    }
    map.invalidate()
}

/** OSM haritası: rota (hız/eğim renkli), başlangıç/bitiş, olay işaretleri ve grafik imleci. */
@Composable
fun RouteMap(r: TripReport, mode: RouteColor, cursor: Int?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            minZoomLevel = 3.0; maxZoomLevel = 19.0
            isTilesScaledToDpi = true
            // kaydırılabilir rapor içinde: dokunurken üst öğenin müdahalesini engelle
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
    }
    val cursorMarker = remember {
        Marker(map).apply {
            icon = dot(0xFF0EA5E9.toInt(), 36); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            isEnabled = false; title = "İmleç"
        }
    }
    val built = remember { booleanArrayOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) map.onResume() else if (ev == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        owner.lifecycle.addObserver(obs)
        map.onResume()
        onDispose { owner.lifecycle.removeObserver(obs); map.onPause(); map.onDetach() }
    }
    LaunchedEffect(r, mode) { rebuild(map, r, mode, cursorMarker, !built[0]); built[0] = true }
    LaunchedEffect(cursor, r) {
        val p = cursor?.let { r.track.getOrNull(it) }
        if (p?.lat != null && p.lon != null) { cursorMarker.position = GeoPoint(p.lat!!, p.lon!!); cursorMarker.isEnabled = true }
        else cursorMarker.isEnabled = false
        map.invalidate()
    }
    Box(modifier) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
        Text("© OpenStreetMap katkıda bulunanlar", fontSize = 10.sp, color = Color.Black,
            modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp))
    }
}
