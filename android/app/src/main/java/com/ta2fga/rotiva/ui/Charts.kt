package com.ta2fga.rotiva.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ta2fga.rotiva.core.*
import kotlin.math.max
import kotlin.math.min

enum class ChartKind { ALT, SPEED }

/**
 * Izgaralı profil grafiği: sol Y ekseni değerleri, alt X ekseni (km veya dk) etiketleri, dokun/sürükle imleci ve ipucu.
 * [cursor] iz noktası dizinidir (TrackPoint.idx); harita ile eşzamanlıdır.
 */
@Composable
fun ProfileChart(
    r: TripReport, kind: ChartKind, cursor: Int?, onCursor: (Int?) -> Unit,
    lineC: Color, rpmC: Color, gearC: Color, evC: Color, gridC: Color, textC: Color, modifier: Modifier,
) {
    val tm = rememberTextMeasurer()
    val track = r.track
    val pts = if (kind == ChartKind.ALT) track.filter { it.alt != null } else track.filter { it.speed != null || it.rpm != null }
    val xOf: (TrackPoint) -> Double = if (kind == ChartKind.ALT) { p -> p.distKm } else { p -> p.t / 60.0 }
    val xs = pts.map(xOf)
    val label = TextStyle(fontSize = 10.sp, color = textC)

    Canvas(
        modifier
            .pointerInput(pts) {
                detectTapGestures { o -> onCursor(pickIndex(o.x, size.width.toFloat(), 46.dp.toPx(), 10.dp.toPx(), xs, pts)) }
            }
            .pointerInput(pts) {
                detectHorizontalDragGestures { change, _ -> change.consume(); onCursor(pickIndex(change.position.x, size.width.toFloat(), 46.dp.toPx(), 10.dp.toPx(), xs, pts)) }
            },
    ) {
        if (pts.size < 2) return@Canvas
        val left = 46.dp.toPx(); val bottom = 20.dp.toPx(); val top = 6.dp.toPx(); val right = 10.dp.toPx()
        val w = size.width - left - right; val h = size.height - top - bottom
        val x0 = xs.first(); val x1 = max(xs.last(), x0 + 1e-6)
        val yTicks = if (kind == ChartKind.ALT) {
            niceTicks(pts.minOf { it.alt!! }, pts.maxOf { it.alt!! }, 4)
        } else niceTicks(0.0, max(60.0, pts.mapNotNull { it.speed }.maxOrNull() ?: 60.0), 4)
        val yLo = yTicks.niceMin; val yHi = max(yTicks.niceMax, yLo + 1e-6)
        val xTicks = niceTicks(x0, x1, 5)
        fun px(x: Double) = (left + (x - x0) / (x1 - x0) * w).toFloat()
        fun py(y: Double) = (top + h - (y - yLo) / (yHi - yLo) * h).toFloat()

        // yatay ızgara + sol eksen etiketleri
        for (v in yTicks.values) {
            val y = py(v)
            drawLine(gridC.copy(alpha = 0.6f), Offset(left, y), Offset(left + w, y), 1f)
            val t = tm.measure(Math.round(v).toString(), label)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y - t.size.height / 2f))
        }
        // dikey ızgara + alt eksen etiketleri
        for (v in xTicks.values) {
            if (v < x0 - 1e-9 || v > x1 + 1e-9) continue
            val x = px(v)
            drawLine(gridC.copy(alpha = 0.6f), Offset(x, top), Offset(x, top + h), 1f)
            val txt = if (kind == ChartKind.ALT) tr1(v, if (xTicks.step < 1) 1 else 0) else Math.round(v).toString()
            val t = tm.measure(txt, label)
            drawText(t, topLeft = Offset(min(max(x - t.size.width / 2f, left - 4f), size.width - t.size.width.toFloat()), top + h + 3.dp.toPx()))
        }
        val unit = tm.measure(if (kind == ChartKind.ALT) "km  /  m" else "dk  /  km/s", label)
        drawText(unit, topLeft = Offset(0f, top + h + 3.dp.toPx()))

        // seri çizgileri
        if (kind == ChartKind.ALT) {
            val path = Path(); val fill = Path()
            pts.forEachIndexed { i, p ->
                val x = px(xs[i]); val y = py(p.alt!!)
                if (i == 0) { path.moveTo(x, y); fill.moveTo(x, top + h); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
            }
            fill.lineTo(px(xs.last()), top + h); fill.close()
            drawPath(fill, lineC.copy(alpha = 0.22f))
            drawPath(path, lineC, style = Stroke(width = 4f))
        } else {
            val rMax = max(4000.0, pts.mapNotNull { it.rpm }.maxOrNull() ?: 4000.0)
            fun series(sel: (TrackPoint) -> Double?, maxV: Double, c: Color, wd: Float, step: Boolean = false) {
                val p = Path(); var started = false; var lastY = 0f
                pts.forEachIndexed { i, pt ->
                    val v = sel(pt)
                    if (v == null) { started = false; return@forEachIndexed }
                    val x = px(xs[i]); val y = (top + h - v / maxV * h).toFloat()
                    if (!started) { p.moveTo(x, y); started = true } else { if (step) p.lineTo(x, lastY); p.lineTo(x, y) }
                    lastY = y
                }
                drawPath(p, c, style = Stroke(width = wd))
            }
            series({ it.rpm }, rMax, rpmC.copy(alpha = 0.85f), 3f)
            series({ it.speed }, yHi, lineC, 4f)
            series({ it.gear?.toDouble() }, 6.0, gearC, 3f, step = true)
            val t0 = r.samples.firstOrNull()?.t ?: 0.0
            r.result.events.forEach { e ->
                val x = px((e.ts - t0) / 60.0)
                if (x in left..(left + w)) {
                    drawLine(evC.copy(alpha = 0.45f), Offset(x, top), Offset(x, top + h), 2f)
                    drawCircle(evC, 5f, Offset(x, top + 6f))
                }
            }
        }

        // imleç
        val cp = cursor?.let { track.getOrNull(it) }
        if (cp != null) {
            val cx = xOf(cp)
            if (cx in x0..x1) {
                val x = px(cx)
                drawLine(textC, Offset(x, top), Offset(x, top + h), 2f)
                val yv = if (kind == ChartKind.ALT) cp.alt else cp.speed
                if (yv != null) {
                    drawCircle(Color.White, 9f, Offset(x, py(yv)))
                    drawCircle(lineC, 6f, Offset(x, py(yv)))
                }
                val tip = tm.measure(if (kind == ChartKind.ALT) altTooltip(cp) else speedTooltip(cp),
                    TextStyle(fontSize = 11.sp, color = Color.White))
                val bw = tip.size.width + 16f; val bh = tip.size.height + 10f
                val bx = min(max(x - bw / 2, left), size.width - bw)
                drawRoundRect(Color(0xCC111111), Offset(bx, top + 2f), Size(bw, bh), CornerRadius(10f, 10f))
                drawText(tip, topLeft = Offset(bx + 8f, top + 7f))
            }
        }
    }
}

private fun pickIndex(px: Float, width: Float, left: Float, right: Float, xs: List<Double>, pts: List<TrackPoint>): Int? {
    if (xs.size < 2) return null
    val frac = ((px - left) / max(1f, width - left - right)).coerceIn(0f, 1f)
    val i = nearestIndex(xs, xs.first() + frac * (xs.last() - xs.first()))
    return if (i >= 0) pts[i].idx else null
}
