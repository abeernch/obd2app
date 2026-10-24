package com.abeernch.civicobd.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.abeernch.civicobd.core.ColorScale
import com.abeernch.civicobd.core.LivePosition
import com.abeernch.civicobd.core.Route
import com.abeernch.civicobd.core.RouteMetric
import com.abeernch.civicobd.core.TrailPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/** Where the map opens before the first GPS fix: Islamabad. */
private val DEFAULT_CENTRE = GeoPoint(33.6844, 73.0479)

/** Closer in town, wider on the motorway, so the road ahead always fits on screen. */
private fun zoomFor(speedKmh: Double?): Double = when {
    speedKmh == null || speedKmh < 35 -> 17.0
    speedKmh < 70 -> 16.0
    else -> 15.0
}

/**
 * The live map on the Drive screen. Follows the car north-up, draws the trail driven so far
 * coloured by economy, and marks the car with an arrow pointing the way it is heading.
 * Touching the map stops following; the caller turns following back on.
 */
@Composable
fun LiveMap(
    trail: List<TrailPoint>,
    position: LivePosition?,
    speedKmh: Double?,
    scale: ColorScale,
    tileKind: MapTiles,
    follow: Boolean,
    onTouched: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = Palette.isDark
    val accent = Palette.Accent.toArgbInt()
    val ink = Palette.Value.toArgbInt()
    var mapRef by remember { mutableStateOf<MapView?>(null) }
    // Last zoom set by following, so the zoom only changes when the speed band does.
    val lastZoom = remember { doubleArrayOf(0.0) }
    DisposableEffect(Unit) { onDispose { mapRef?.onDetach() } }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            newMap(ctx, dark, tileKind).also { map ->
                val overlay = TrailOverlay(ctx.resources.displayMetrics.density, onTouched)
                map.overlays.add(overlay)
                map.tag = overlay
                mapRef = map
                val start = position?.let { GeoPoint(it.lat, it.lon) } ?: DEFAULT_CENTRE
                map.controller.setZoom(if (position != null) zoomFor(speedKmh) else 12.0)
                map.controller.setCenter(start)
            }
        },
        update = { map ->
            val overlay = map.tag as TrailOverlay
            overlay.trail = trail
            overlay.position = position
            overlay.scale = scale
            overlay.accent = accent
            overlay.ink = ink
            map.useTiles(dark, tileKind)
            if (follow && position != null) {
                val zoom = zoomFor(speedKmh)
                val target = GeoPoint(position.lat, position.lon)
                if (zoom != lastZoom[0]) {
                    lastZoom[0] = zoom
                    map.controller.animateTo(target, zoom, 900L)
                } else {
                    map.controller.animateTo(target)
                }
            }
            map.invalidate()
        },
    )
}

private class TrailOverlay(
    private val density: Float,
    private val onTouched: () -> Unit,
) : Overlay() {
    var trail: List<TrailPoint> = emptyList()
    var position: LivePosition? = null
    var scale: ColorScale = ColorScale.Parula
    var accent: Int = 0xFFFF8000.toInt()
    var ink: Int = 0xFFFFFFFF.toInt()

    private val p0 = Point()
    private val p1 = Point()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val arrow = Path()

    // Fixed economy range, the same as the route map's "Fixed" scale, so colours mean the same every drive.
    private val range = RouteMetric.Economy.fixedLow to RouteMetric.Economy.fixedHigh

    private fun colorOf(p: TrailPoint): Int = when {
        p.fuelCut -> Route.FUEL_CUT_COLOR
        p.kmPerL == null -> 0xFF8C8C92.toInt()
        else -> Route.color(scale, Route.position(p.kmPerL, range, RouteMetric.Economy))
    }

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        if (trail.size >= 2) {
            // Dark casing first, then the coloured line on top.
            line.strokeWidth = 9f * density
            line.color = 0x99000000.toInt()
            proj.toPixels(GeoPoint(trail[0].lat, trail[0].lon), p0)
            for (i in 1 until trail.size) {
                proj.toPixels(GeoPoint(trail[i].lat, trail[i].lon), p1)
                canvas.drawLine(p0.x.toFloat(), p0.y.toFloat(), p1.x.toFloat(), p1.y.toFloat(), line)
                p0.set(p1.x, p1.y)
            }
            line.strokeWidth = 6f * density
            proj.toPixels(GeoPoint(trail[0].lat, trail[0].lon), p0)
            for (i in 1 until trail.size) {
                proj.toPixels(GeoPoint(trail[i].lat, trail[i].lon), p1)
                line.color = colorOf(trail[i])
                canvas.drawLine(p0.x.toFloat(), p0.y.toFloat(), p1.x.toFloat(), p1.y.toFloat(), line)
                p0.set(p1.x, p1.y)
            }
        }

        val pos = position ?: return
        proj.toPixels(GeoPoint(pos.lat, pos.lon), p0)
        val x = p0.x.toFloat()
        val y = p0.y.toFloat()

        // GPS accuracy as a faint disc, when it is wider than the marker.
        pos.accuracyM?.let { acc ->
            val r = proj.metersToPixels(acc.toFloat())
            if (r > 16f * density) {
                fill.color = (accent and 0x00FFFFFF) or 0x22000000
                canvas.drawCircle(x, y, r, fill)
            }
        }

        val bearing = pos.bearing
        if (bearing == null) {
            // Not moved yet: a dot.
            fill.color = 0x66000000
            canvas.drawCircle(x, y, 13f * density, fill)
            fill.color = accent
            canvas.drawCircle(x, y, 8f * density, fill)
            edge.color = ink; edge.strokeWidth = 2.5f * density
            canvas.drawCircle(x, y, 8f * density, edge)
            return
        }
        // An arrow pointing the way the car is heading, drawn pointing up and then rotated.
        val s = density
        arrow.reset()
        arrow.moveTo(0f, -15f * s)
        arrow.lineTo(11f * s, 12f * s)
        arrow.lineTo(0f, 6f * s)
        arrow.lineTo(-11f * s, 12f * s)
        arrow.close()
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(bearing.toFloat())
        fill.color = 0x66000000
        canvas.drawCircle(0f, 0f, 19f * s, fill)
        fill.color = accent
        canvas.drawPath(arrow, fill)
        edge.color = ink; edge.strokeWidth = 2.5f * s
        canvas.drawPath(arrow, edge)
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent, map: MapView): Boolean {
        // Any touch hands the map to the driver; returning false still lets it pan and zoom.
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) onTouched()
        return false
    }
}
