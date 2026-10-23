package com.abeernch.civicobd.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abeernch.civicobd.core.Series
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Line colours for combined plots. White first, then hues that stay readable on black. */
val SeriesColors = listOf(
    Color(0xFFFFFFFF), Color(0xFF4CC9E0), Color(0xFFF2A33A), Color(0xFF7BD389),
    Color(0xFFB69CF2), Color(0xFFF28CB1), Color(0xFFE8E37A), Color(0xFF8AB4F8),
)

/**
 * Shared between all charts of one recording, so zoom and the marker stay in step
 * when parameters are plotted separately.
 */
class ChartView(val fullStart: Long, val fullEnd: Long) {
    var start by mutableStateOf(fullStart)
    var end by mutableStateOf(maxOf(fullEnd, fullStart + 1000))
    var cursor by mutableStateOf<Long?>(null)

    val zoomed: Boolean get() = start > fullStart || end < fullEnd

    fun reset() {
        start = fullStart
        end = maxOf(fullEnd, fullStart + 1000)
    }

    /** Zooms around a point given as a fraction of the width, and pans by a fraction of the width. */
    fun zoomPan(zoom: Float, centre: Float, pan: Float) {
        val width = (end - start).toDouble()
        val newWidth = (width / zoom).coerceIn(10_000.0, (fullEnd - fullStart).toDouble().coerceAtLeast(10_000.0))
        val anchor = start + width * centre
        var s = anchor - newWidth * centre - pan * newWidth
        s = s.coerceIn(fullStart.toDouble(), (fullEnd - newWidth).coerceAtLeast(fullStart.toDouble()))
        start = s.toLong()
        end = (s + newWidth).toLong()
    }
}

/**
 * Interactive line chart.
 * Tap or slide a finger sideways to place the marker. Pinch with two fingers to zoom, drag with two to pan.
 * Double tap to zoom back out. In [normalised] mode each line uses its own scale (for combined plots).
 */
@Composable
fun LineChart(
    series: List<Series>,
    colors: List<Color>,
    view: ChartView,
    normalised: Boolean,
    height: Dp,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = B612, fontSize = 10.sp, color = Palette.Label)
    val clock = SimpleDateFormat("HH:mm", Locale.US)
    val clockSec = SimpleDateFormat("HH:mm:ss", Locale.US)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(view) {
                detectTapGestures(
                    onTap = { p -> view.cursor = timeAt(view, p.x, size.width.toFloat()) },
                    onDoubleTap = { view.reset() },
                )
            }
            .pointerInput(view) {
                detectHorizontalDragGestures { change, _ ->
                    view.cursor = timeAt(view, change.position.x, size.width.toFloat())
                }
            }
            .pointerInput(view) {
                // Two-finger pinch and pan. One finger is left to the marker and to page scrolling.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val w = size.width.toFloat()
                            view.zoomPan(
                                zoom = event.calculateZoom(),
                                centre = (event.calculateCentroid().x / w).coerceIn(0f, 1f),
                                pan = event.calculatePan().x / w,
                            )
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        val left = if (normalised) 0f else 40.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val top = 6.dp.toPx()
        val w = size.width - left
        val span = (view.end - view.start).toFloat().coerceAtLeast(1f)
        fun x(t: Long) = left + (t - view.start) / span * w

        // Horizontal grid: four lines
        for (i in 0..3) {
            val y = top + (bottom - top) * i / 3f
            drawLine(Palette.Hairline, Offset(left, y), Offset(size.width, y), 1f)
        }

        // Time labels at the edges and the middle
        listOf(0f, 0.5f, 1f).forEach { f ->
            val t = (view.start + span * f).toLong()
            val text = (if (span < 10 * 60_000) clockSec else clock).format(Date(t))
            val layout = measurer.measure(text, labelStyle)
            val tx = (left + w * f - layout.size.width * f).coerceAtLeast(left)
            drawText(layout, topLeft = Offset(tx, bottom + 4.dp.toPx()))
        }

        series.forEachIndexed { index, s ->
            if (s.size == 0) return@forEachIndexed
            val color = colors.getOrElse(index) { Palette.Value }
            val from = (s.indexAt(view.start) - 1).coerceAtLeast(0)
            val to = (s.indexAt(view.end) + 1).coerceAtMost(s.size - 1)
            if (to < from) return@forEachIndexed

            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (i in from..to) {
                lo = minOf(lo, s.v[i]); hi = maxOf(hi, s.v[i])
            }
            if (hi - lo < 1e-6f) { hi += 1f; lo -= 1f }
            val pad = (hi - lo) * 0.08f
            lo -= pad; hi += pad
            fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)

            // Draw at most about two points per pixel column.
            val step = maxOf(1, (to - from) / (w.toInt() * 2).coerceAtLeast(1))
            val path = Path()
            var first = true
            var i = from
            while (i <= to) {
                val px = x(s.t[i]); val py = y(s.v[i])
                if (first) path.moveTo(px, py) else path.lineTo(px, py)
                first = false
                i += step
            }
            drawPath(path, color, style = Stroke(width = 1.6.dp.toPx()))

            if (!normalised) {
                // Value scale on the left for single-parameter charts
                listOf(hi - pad, (hi + lo) / 2, lo + pad).forEach { v ->
                    val layout = measurer.measure(compact(v), labelStyle)
                    val ty = (y(v) - layout.size.height / 2f).coerceIn(0f, bottom - layout.size.height)
                    drawText(layout, topLeft = Offset(0f, ty))
                }
            }

            // Marker dot on this line
            val c = view.cursor
            if (c != null && c in view.start..view.end) {
                val j = s.indexAt(c)
                if (j >= 0) drawCircle(color, 3.5.dp.toPx(), Offset(x(s.t[j]), y(s.v[j])))
            }
        }

        // Marker line
        val c = view.cursor
        if (c != null && c in view.start..view.end) {
            drawLine(
                Palette.Target, Offset(x(c), top), Offset(x(c), bottom), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }
    }
}

private fun timeAt(view: ChartView, px: Float, width: Float): Long {
    val f = (px / width).coerceIn(0f, 1f)
    return view.start + ((view.end - view.start) * f).toLong()
}

/** Short number for axis labels: 2700, 14.2, 0.05 */
fun compact(v: Float): String = when {
    kotlin.math.abs(v) >= 100 -> "%.0f".format(v)
    kotlin.math.abs(v) >= 10 -> "%.1f".format(v)
    else -> "%.2f".format(v)
}
