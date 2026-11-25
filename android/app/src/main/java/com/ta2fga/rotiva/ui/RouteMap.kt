package com.ta2fga.rotiva.ui

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
import com.ta2fga.rotiva.BuildConfig
import com.ta2fga.rotiva.core.TripReport
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

// Mapnik altlığı yeşil/sarı/pembe/açık tonlarla dolu: rota koyu bir "kılıf" üzerine,
// harita renklerinden ayrışan doygun ve algısal olarak sıralı renklerle çizilir.
/** Hız: koyu mavi → camgöbeği → sarı → turuncu → koyu kırmızı/macenta. */
internal val SPEED_COLORS = intArrayOf(
    0xFF2B3FD6.toInt(), 0xFF00B8D9.toInt(), 0xFFFFE014.toInt(), 0xFFFF7A00.toInt(), 0xFFD1005E.toInt(),
)
/** Eğim (ıraksak): koyu mavi (iniş) ↔ açık gri/beyaz (düz) ↔ koyu kırmızı (yokuş). */
internal val GRADE_COLORS = intArrayOf(
    0xFF1440B8.toInt(), 0xFF5B9CF2.toInt(), 0xFFF2F2F2.toInt(), 0xFFF26B3A.toInt(), 0xFFB0101E.toInt(),
)
internal const val PLAIN_COLOR = 0xFF0050FF.toInt()
/** Rota ve işaretlerin altındaki koyu kılıf/kontur. */
internal const val CASING_COLOR = 0xF0101418.toInt()
private const val LINE_DP = 4.5f
private const val CASING_DP = 7.5f

fun speedBucket(v: Double?): Int = when { v == null -> 0; v < 30 -> 0; v < 60 -> 1; v < 90 -> 2; v < 120 -> 3; else -> 4 }
fun gradeBucket(g: Double?): Int = when { g == null -> 2; g < -6 -> 0; g < -2 -> 1; g <= 2 -> 2; g <= 6 -> 3; else -> 4 }

fun legendFor(mode: RouteColor): List<Pair<String, Int>> = when (mode) {
    RouteColor.PLAIN -> emptyList()
    RouteColor.SPEED -> listOf("<30" to 0, "30-60" to 1, "60-90" to 2, "90-120" to 3, ">120 km/s" to 4)
        .map { it.first to SPEED_COLORS[it.second] }
    RouteColor.GRADE -> listOf("<-6%" to 0, "-6..-2" to 1, "düz" to 2, "2..6" to 3, ">6% yokuş" to 4)
        .map { it.first to GRADE_COLORS[it.second] }
}

/** Haritada rota rengi (lejantla aynı paletten). */
fun routeColorOf(mode: RouteColor, speed: Double?, grade: Double?): Int = when (mode) {
    RouteColor.PLAIN -> PLAIN_COLOR
    RouteColor.SPEED -> SPEED_COLORS[speedBucket(speed)]
    RouteColor.GRADE -> GRADE_COLORS[gradeBucket(grade)]
}

enum class MarkerShape { DOT, SQUARE }
data class EventStyle(val label: String, val color: Int, val shape: MarkerShape = MarkerShape.DOT)

/** Olay türü → işaret stili. core/Scoring.kt LABELS'taki tüm türler + "pothole". */
internal val EVENT_STYLE: Map<String, EventStyle> = mapOf(
    "harsh_brake" to EventStyle("Sert fren", 0xFFE11D48.toInt()),
    "harsh_accel" to EventStyle("Sert hızlanma", 0xFFFF8A00.toInt()),
    "harsh_corner" to EventStyle("Sert viraj", 0xFF9333EA.toInt()),
    "over_rev" to EventStyle("Yüksek devir", 0xFFFF2BD6.toInt()),
    "lugging" to EventStyle("Motoru zorlama", 0xFFFACC15.toInt()),
    "lug_uphill" to EventStyle("Yokuşta düşük devir", 0xFFFACC15.toInt()),
    "late_upshift" to EventStyle("Geç vites büyütme", 0xFF14B8A6.toInt()),
    "early_upshift" to EventStyle("Erken vites büyütme", 0xFF5EEAD4.toInt()),
    "cold_abuse" to EventStyle("Soğuk motora yüklenme", 0xFF38BDF8.toInt()),
    "long_idle" to EventStyle("Uzun rölanti", 0xFF9CA3AF.toInt()),
    "unstable_speed" to EventStyle("Dengesiz hız", 0xFF84CC16.toInt()),
    "descent_brake" to EventStyle("İnişte sürekli fren", 0xFF7F1D1D.toInt()),
    "pothole" to EventStyle("Çukur", 0xFF8B4513.toInt(), MarkerShape.SQUARE),
)
internal const val FALLBACK_EVENT_COLOR = 0xFFE5E7EB.toInt()

/** Bilinmeyen türler sessizce düşürülmez: olayın kendi etiketiyle nötr bir işaret. */
fun eventStyleFor(type: String, label: String?): EventStyle =
    EVENT_STYLE[type] ?: EventStyle(label?.takeIf { it.isNotBlank() } ?: type, FALLBACK_EVENT_COLOR)

private fun markerIcon(color: Int, sizePx: Int, strokePx: Int, shape: MarkerShape = MarkerShape.DOT) = GradientDrawable().apply {
    if (shape == MarkerShape.SQUARE) { this.shape = GradientDrawable.RECTANGLE; cornerRadius = sizePx * 0.18f }
    else this.shape = GradientDrawable.OVAL
    setColor(color); setStroke(strokePx, CASING_COLOR); setSize(sizePx, sizePx)
}

/** Altlığı hafifçe soldur/karart: rota öne çıksın, harita okunur kalsın. */
private fun mutedTiles() = ColorMatrixColorFilter(ColorMatrix().apply {
    setSaturation(0.45f)
    postConcat(ColorMatrix().apply { setScale(0.94f, 0.94f, 0.94f, 1f) })
})

private fun rebuild(map: MapView, r: TripReport, mode: RouteColor, showEvents: Boolean, cursorMarker: Marker, first: Boolean) {
    map.overlays.clear()
    val pts = r.track.filter { it.lat != null && it.lon != null }
    if (pts.size < 2) return
    val d = map.resources.displayMetrics.density
    val geo = pts.map { GeoPoint(it.lat!!, it.lon!!) }
    // önce tüm rotanın altına tek parça koyu kılıf
    map.overlays.add(Polyline().apply {
        setPoints(geo)
        outlinePaint.color = CASING_COLOR; outlinePaint.strokeWidth = CASING_DP * d
        outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.strokeJoin = Paint.Join.ROUND
        setOnClickListener { _, _, _ -> false }
    })
    // rota: aynı renk kümesindeki ardışık parçalar tek çizgi
    val colors = IntArray(pts.size) { routeColorOf(mode, pts[it].speed, pts[it].grade) }
    var runStart = 0
    for (i in 1..pts.size) {
        if (i == pts.size || colors[i] != colors[runStart]) {
            val end = if (i == pts.size) pts.size - 1 else i
            val line = Polyline().apply {
                setPoints(geo.subList(runStart, end + 1))
                outlinePaint.color = colors[runStart]; outlinePaint.strokeWidth = LINE_DP * d
                outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.strokeJoin = Paint.Join.ROUND
                setOnClickListener { _, _, _ -> false }
            }
            map.overlays.add(line)
            runStart = end
            if (i == pts.size) break
        }
    }
    val stroke = (2.5f * d).toInt().coerceAtLeast(2)
    fun marker(p: GeoPoint, icon: GradientDrawable, title: String?, snippet: String?) = Marker(map).apply {
        position = p; this.icon = icon; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        this.title = title; this.snippet = snippet
    }
    if (showEvents) for (ep in r.eventPositions) {
        val st = eventStyleFor(ep.event.type, ep.event.label)
        map.overlays.add(marker(GeoPoint(ep.lat, ep.lon), markerIcon(st.color, (13 * d).toInt(), stroke, st.shape),
            st.label, "${ep.event.text} · ${com.ta2fga.rotiva.core.fmtDuration(ep.event.t)}"))
    }
    map.overlays.add(marker(geo.first(), markerIcon(0xFF16A34A.toInt(), (18 * d).toInt(), (3 * d).toInt()), "Başlangıç", null))
    map.overlays.add(marker(geo.last(), markerIcon(0xFFDC2626.toInt(), (18 * d).toInt(), (3 * d).toInt()), "Bitiş", null))
    map.overlays.add(cursorMarker)
    if (first) {
        var box = BoundingBox.fromGeoPoints(geo)
        if (box.latNorth - box.latSouth < 1e-4 || box.lonEast - box.lonWest < 1e-4) {
            box = BoundingBox(box.latNorth + 0.002, box.lonEast + 0.002, box.latSouth - 0.002, box.lonWest - 0.002)
        }
        val z = { map.zoomToBoundingBox(box, false, 80) }
        if (map.width > 0) z() else map.addOnFirstLayoutListener { _, _, _, _, _ -> z() }
    }
    map.invalidate()
}

/** OSM haritası: rota (hız/eğim renkli), başlangıç/bitiş, olay işaretleri (isteğe bağlı) ve grafik imleci. */
@Composable
fun RouteMap(r: TripReport, mode: RouteColor, cursor: Int?, modifier: Modifier = Modifier, showEvents: Boolean = true) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            minZoomLevel = 3.0; maxZoomLevel = 19.0
            isTilesScaledToDpi = true
            overlayManager.tilesOverlay.setColorFilter(mutedTiles())
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
            val d = ctx.resources.displayMetrics.density
            icon = markerIcon(0xFF00E5FF.toInt(), (15 * d).toInt(), (3 * d).toInt()); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
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
    // geçişlerde yeniden çiz ama yeniden yakınlaştırma (ilk kurulumda bir kez)
    LaunchedEffect(r, mode, showEvents) { rebuild(map, r, mode, showEvents, cursorMarker, !built[0]); built[0] = true }
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
