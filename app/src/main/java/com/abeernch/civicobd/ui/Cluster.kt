package com.abeernch.civicobd.ui

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Shift { Up, Down }

/** Everything the cluster shows. */
data class ClusterState(
    val speed: Double,
    val live: Boolean,            // the engine is running and readings are coming in
    val instant: Double?,         // km/L now; null when standing still
    val fuelCut: Boolean,         // coasting in gear, no fuel used
    val average: Double?,         // this trip's km/L: the accent tick on the ring
    val gear: Int?,               // 0 is neutral, 1 to 5, null when not known
    val shift: Shift?,
    val fuelFraction: Double?,    // 0..1 from the last fill-up, null when unknown
    val rangeKm: Double?,
    val label: String,            // under the instant figure: km/L NOW, IDLE, FUEL CUT
)

// Geometry of the approved design, in its own 390 x 352 units (y from 64 to 416).
private const val CX = 195f
private const val CY = 230f
private const val R = 106f
private const val PANEL_R = 165f
private const val VIEW_W = 390f
private const val VIEW_H = 352f
private const val VIEW_TOP = 64f
private fun ang(v: Float) = 135f + 9f * v

private val WakeEase = CubicBezierEasing(0.45f, 0f, 0.25f, 1f)
private val GEARS = listOf("5", "4", "3", "2", "1", "N")
private val GEAR_ANGLES = listOf(-30f, -18f, -6f, 6f, 18f, 30f)

/**
 * The Drive tab's instrument: speed in the middle inside a thin economy ring (instant km/L, with the
 * trip average as an accent tick), the gears on a curved panel to the right with the current one lit,
 * and the fuel cells on a curved panel to the left.
 * [wake] is seconds into the start-up sweep (negative: about to start), or null once awake.
 */
@Composable
fun DriveCluster(state: ClusterState, modifier: Modifier = Modifier, wake: Float? = null) {
    val measurer = rememberTextMeasurer(cacheSize = 48)
    val ringTarget = (if (state.fuelCut) 30.0 else state.instant ?: 0.0).coerceIn(0.0, 30.0).toFloat()
    val ring by animateFloatAsState(ringTarget, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow), label = "ring")
    val avg by animateFloatAsState((state.average ?: 0.0).coerceIn(0.0, 30.0).toFloat(), spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessVeryLow), label = "avg")
    val speedShown by animateFloatAsState(state.speed.toFloat().coerceAtLeast(0f), tween(700), label = "speed")
    val fuel by animateFloatAsState((state.fuelFraction ?: 0.0).toFloat().coerceIn(0f, 1f), tween(900), label = "fuel")
    val gearIndex = when (state.gear) { null -> -1; 0 -> 5; else -> 5 - state.gear.coerceIn(1, 5) }
    val gearFade = GEARS.indices.map { i ->
        animateFloatAsState(if (i == gearIndex) 1f else 0f, tween(260), label = "gear$i").value
    }
    val up by animateFloatAsState(if (state.shift == Shift.Up) 1f else 0f, tween(220), label = "up")
    val down by animateFloatAsState(if (state.shift == Shift.Down) 1f else 0f, tween(220), label = "down")

    val accent = Lux.Gold
    val accentSoft = Lux.GoldSoft
    val red = Lux.Red
    val dark = Palette.isDark
    val speedText = speedShown.roundToInt().toString()
    val description = buildString {
        append("Speed ${state.speed.roundToInt()} km/h. ")
        append(
            when {
                state.fuelCut -> "Fuel cut. "
                state.instant != null -> "${fmt(state.instant)} km/L now. "
                else -> state.label.lowercase().replaceFirstChar { it.uppercase() } + ". "
            }
        )
        state.average?.let { append("Trip average ${fmt(it)} km/L. ") }
        append(if (state.gear == null) "" else if (state.gear == 0) "Neutral. " else "Gear ${state.gear}. ")
        state.fuelFraction?.let { append("Fuel ${(it * 100).roundToInt()} percent.") }
    }

    Canvas(modifier.semantics { contentDescription = description }) {
        val s = minOf(size.width / VIEW_W, size.height / VIEW_H)
        val ox = (size.width - VIEW_W * s) / 2
        val oy = (size.height - VIEW_H * s) / 2 - VIEW_TOP * s
        val g = Geo(s, ox, oy, this)
        val t = wake?.coerceAtLeast(0f)

        // ---- the wake sweep, or the live values
        val shownRing = when {
            t == null -> ring
            t < 0.75f -> 30f * WakeEase.transform(t / 0.75f)
            else -> 30f + (ring - 30f) * WakeEase.transform(((t - 0.75f) / 0.75f).coerceAtMost(1f))
        }
        val speedAlpha = if (t == null) 1f else span(t, 0.8f, 1.4f)
        val instAlpha = if (t == null) 1f else span(t, 1.1f, 1.6f)
        val gearAlpha = if (t == null) 1f else span(t, 0.7f, 1.0f)

        drawStatic(g, measurer, dark)

        // ---- economy ring
        val col = if (state.fuelCut) Lux.Cut else Lux.eco(if (state.instant == null && !state.fuelCut) null else shownRing.toDouble())
        if (shownRing > 0.05f) {
            val sweep = 9f * shownRing
            glowArc(g, R, 135f, sweep, 7f, state.fuelCut, 0.35f)
            drawArc(
                brush = if (state.fuelCut) Brush.linearGradient(listOf(Lux.Cut, Lux.Cut)) else ecoBrush(g),
                startAngle = 135f, sweepAngle = sweep, useCenter = false,
                topLeft = g.p(CX - R, CY - R), size = Size(2 * R * s, 2 * R * s),
                style = Stroke(4f * s, cap = StrokeCap.Round),
            )
        }
        // The knob at the instant value.
        val ka = Math.toRadians(ang(shownRing).toDouble())
        val knob = g.p(CX + R * cos(ka).toFloat(), CY + R * sin(ka).toFloat())
        val knobCol = if (t != null) Lux.eco(shownRing.toDouble()) else if (state.live) col else Lux.Faint
        drawCircle(knobCol.copy(alpha = 0.7f), 9f * s, knob, style = Stroke(1.2f * s))
        drawCircle(if (dark) Color(0xFFF4F5F7) else Palette.Value, 4.6f * s, knob)

        // The trip average: an accent tick across the ring, labelled AVG.
        if (state.average != null && (t == null || t > 1.2f)) {
            val a = Math.toRadians(ang(avg).toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val p0 = g.p(CX + (R - 9) * ca, CY + (R - 9) * sa)
            val p1 = g.p(CX + (R + 9) * ca, CY + (R + 9) * sa)
            drawLine(if (dark) Color(0xFF050608) else Palette.Background, p0, p1, 5f * s, StrokeCap.Round)
            drawLine(accent, p0, p1, 2.2f * s, StrokeCap.Round)
            text(measurer, "AVG", g.p(CX + (R + 23) * ca, CY + (R + 23) * sa), lux(g.sp(8.5f), FontWeight.Medium, accent, g.sp(1.8f)), centreY = true)
        }

        // ---- gears: the lit one bright with a soft halo and an accent dot
        for (i in GEARS.indices) {
            val a = Math.toRadians(GEAR_ANGLES[i].toDouble())
            val c = g.p(CX + PANEL_R * cos(a).toFloat(), CY + PANEL_R * sin(a).toFloat())
            val sweepFlash = if (t != null && i < 5) {
                val ti = 0.05f + 0.1f * (5 - i)
                span(t, ti, ti + 0.08f, androidx.compose.animation.core.LinearEasing) * (1f - span(t, ti + 0.08f, ti + 0.3f, androidx.compose.animation.core.LinearEasing))
            } else 0f
            val lit = gearFade[i] * gearAlpha
            val dim = if (dark) Color(0xFF3C424C) else Palette.Label.copy(alpha = 0.5f)
            if (lit < 1f) text(measurer, GEARS[i], c, lux(g.sp(14f), FontWeight.Normal, dim), centreY = true, alpha = 1f - lit)
            if (sweepFlash > 0f) text(measurer, GEARS[i], c, lux(g.sp(14f), FontWeight.Normal, Lux.Txt), centreY = true, alpha = sweepFlash)
            if (lit > 0f) {
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = if (dark) 0.2f * lit else 0f), Color.Transparent), center = c, radius = 19f * s),
                    19f * s, c,
                )
                text(measurer, GEARS[i], c, lux(g.sp(19f), FontWeight.Medium, if (dark) Color.White else Palette.Value), centreY = true, alpha = lit)
                val d = g.p(CX + (PANEL_R - 25) * cos(a).toFloat(), CY + (PANEL_R - 25) * sin(a).toFloat())
                drawCircle(accent.copy(alpha = lit), 2.2f * s, d)
            }
        }
        // Shift chevrons above and below the gears.
        chevron(g, -38f, -1f, up, accent)
        chevron(g, 38f, 1f, down, accent)

        // ---- fuel cells, bottom (E) to top (F)
        val known = state.fuelFraction != null
        val litCells = if (known) maxOf(1, (fuel * 10).roundToInt()) else 0
        val low = known && fuel < 0.15f
        val onCol = if (low) red else accent
        for (i in 0 until 10) {
            val a = Math.toRadians((148f + i * 64f / 9f).toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val p0 = g.p(CX + (PANEL_R - 7) * ca, CY + (PANEL_R - 7) * sa)
            val p1 = g.p(CX + (PANEL_R + 7) * ca, CY + (PANEL_R + 7) * sa)
            var on = if (i < litCells) 1f else 0f
            if (t != null && i >= litCells) {
                val upAt = 0.05f + 0.06f * i
                val downAt = 0.8f + 0.05f * (9 - i)
                on = span(t, upAt, upAt + 0.06f, androidx.compose.animation.core.LinearEasing) * (1f - span(t, downAt, downAt + 0.15f, androidx.compose.animation.core.LinearEasing))
            }
            val off = if (dark) Color(0xFF22262D) else Palette.Hairline
            drawLine(lerp(off, if (i < litCells) onCol else accent, on), p0, p1, 2.4f * s, StrokeCap.Round)
        }

        // ---- centre figures
        val speedCol = if (state.live || t != null) (if (dark) Color(0xFFEEF0F3) else Palette.Value) else Lux.Faint
        text(measurer, speedText, g.p(CX, CY + 26f), lux(g.sp(96f), FontWeight.ExtraLight, speedCol, g.sp(-1f)), baseline = true, alpha = speedAlpha)
        text(measurer, "km/h", g.p(CX, CY + 50f), lux(g.sp(10.5f), FontWeight.Normal, Lux.Dim, g.sp(3f)), baseline = true, alpha = speedAlpha)
        val instText = when {
            state.fuelCut -> "cut"
            state.instant != null -> fmt(state.instant)
            else -> "–"
        }
        val instCol = if (state.fuelCut) Lux.Cut else if (state.instant != null) Lux.eco(state.instant) else Lux.Sub
        text(measurer, instText, g.p(CX, CY + 100f), lux(g.sp(24f), FontWeight.Normal, instCol), baseline = true, alpha = instAlpha)
        text(measurer, state.label, g.p(CX, CY + 116f), lux(g.sp(9f), FontWeight.Normal, Lux.Dim, g.sp(2.2f)), baseline = true, alpha = instAlpha)

        // ---- side labels: fuel left, gear or shift right
        val fuelText = if (known) "${(fuel * 100).roundToInt()}%" else "–%"
        text(measurer, fuelText, g.p(64f, 390f), lux(g.sp(13f), FontWeight.Normal, if (low) red else if (known) accentSoft else Lux.Faint), baseline = true)
        text(
            measurer, state.rangeKm?.let { "${it.roundToInt()} km" } ?: if (known) "" else "log a fill",
            g.p(64f, 404f), lux(g.sp(9.5f), FontWeight.Normal, Lux.Dim, g.sp(0.6f)), baseline = true,
        )
        val (shiftText, shiftCol) = when (state.shift) {
            Shift.Up -> "SHIFT UP" to accent
            Shift.Down -> "SHIFT DOWN" to Lux.Amber
            null -> "GEAR" to (if (dark) Color(0xFF4A515B) else Palette.Label)
        }
        text(measurer, shiftText, g.p(326f, 396f), lux(g.sp(9f), FontWeight.Medium, shiftCol, g.sp(2f)), baseline = true)
    }
}

/** Maps the design's units onto the canvas. */
private class Geo(val s: Float, val ox: Float, val oy: Float, val density: androidx.compose.ui.unit.Density) {
    fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
    /** A text size given in design units, as sp for this canvas. */
    fun sp(units: Float) = with(density) { (units * s).toSp() }
}

/** Static parts: the disc, bezel, ticks, scale labels, and the two curved panels. */
private fun DrawScope.drawStatic(g: Geo, measurer: TextMeasurer, dark: Boolean) {
    val s = g.s
    val c = g.p(CX, CY)
    if (dark) {
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF11151B), Color(0x0011151B)), center = g.p(CX, CY - 10f), radius = 100f * s),
            100f * s, c,
        )
    }
    drawCircle(if (dark) Color(0xFF12151A) else Palette.Hairline, 128f * s, c, style = Stroke(1f * s))
    // A faint sheen along the upper left of the bezel.
    drawArc(
        brush = Brush.horizontalGradient(
            0f to Color.White.copy(alpha = 0f), 0.3f to Color.White.copy(alpha = if (dark) 0.28f else 0f),
            0.6f to Color.White.copy(alpha = if (dark) 0.08f else 0f), 1f to Color.White.copy(alpha = 0f),
            startX = g.p(60f, 0f).x, endX = g.p(330f, 0f).x,
        ),
        startAngle = 182f, sweepAngle = 118f, useCenter = false,
        topLeft = g.p(CX - 128f, CY - 128f), size = Size(256f * s, 256f * s), style = Stroke(1f * s),
    )
    // Ticks every km/L, longer every five.
    for (v in 0..30) {
        val a = Math.toRadians(ang(v.toFloat()).toDouble())
        val major = v % 5 == 0
        val r0 = R + 9
        val r1 = if (major) R + 14 else R + 11
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        drawLine(
            if (dark) (if (major) Color(0xFF2E333B) else Color(0xFF191C22)) else Palette.Hairline,
            g.p(CX + r0 * ca, CY + r0 * sa), g.p(CX + r1 * ca, CY + r1 * sa),
            (if (major) 1.2f else 1f) * s, StrokeCap.Round,
        )
    }
    val scaleCol = if (dark) Color(0xFF4A515B) else Palette.Label
    for ((v, label) in listOf(0f to "0", 30f to "30")) {
        val a = Math.toRadians(ang(v).toDouble())
        text(measurer, label, g.p(CX + (R + 24) * cos(a).toFloat(), CY + (R + 24) * sin(a).toFloat()), lux(g.sp(10f), FontWeight.Normal, scaleCol), centreY = true)
    }
    // The ring's track.
    drawArc(
        if (dark) Color(0xFF15181D) else Palette.Hairline, 135f, 270f, false,
        topLeft = g.p(CX - R, CY - R), size = Size(2 * R * s, 2 * R * s), style = Stroke(4f * s, cap = StrokeCap.Round),
    )
    // The two side panels: slim curved bands with a hairline edge.
    band(g, -42f, 84f, dark)
    band(g, 138f, 84f, dark)
    text(measurer, "E", polar(g, PANEL_R, 140.5f), lux(g.sp(9f), FontWeight.Medium, scaleCol), centreY = true)
    text(measurer, "F", polar(g, PANEL_R, 219.5f), lux(g.sp(9f), FontWeight.Medium, scaleCol), centreY = true)
}

private fun polar(g: Geo, r: Float, deg: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    return g.p(CX + r * cos(a).toFloat(), CY + r * sin(a).toFloat())
}

private fun DrawScope.band(g: Geo, start: Float, sweep: Float, dark: Boolean) {
    val s = g.s
    val tl = g.p(CX - PANEL_R, CY - PANEL_R)
    val sz = Size(2 * PANEL_R * s, 2 * PANEL_R * s)
    drawArc(if (dark) Color(0xFF171A20) else Palette.Hairline, start, sweep, false, tl, sz, style = Stroke(36f * s, cap = StrokeCap.Round))
    drawArc(if (dark) Color(0xFF08090B) else Palette.Surface, start, sweep, false, tl, sz, style = Stroke(34f * s, cap = StrokeCap.Round))
}

/** The ring's colours from poor to good, laid across the ring from left to right. */
private fun ecoBrush(g: Geo) = Brush.horizontalGradient(
    0f to Lux.Red, 0.42f to Lux.Amber, 0.7f to lerp(Lux.Amber, Lux.Green, 0.62f), 1f to Lux.Green,
    startX = g.p(86f, 0f).x, endX = g.p(304f, 0f).x,
)

/** A soft glow under the ring's fill, where the phone can blur it. */
private fun DrawScope.glowArc(g: Geo, r: Float, start: Float, sweep: Float, width: Float, cut: Boolean, alpha: Float) {
    if (Build.VERSION.SDK_INT < 28 || !Palette.isDark) return
    val s = g.s
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = width * s
            strokeCap = android.graphics.Paint.Cap.ROUND
            this.alpha = (alpha * 255).toInt()
            maskFilter = BlurMaskFilter(2.5f * s * 1.6f, BlurMaskFilter.Blur.NORMAL)
            if (cut) {
                color = Lux.Cut.copy(alpha = alpha).toArgb()
            } else {
                shader = android.graphics.LinearGradient(
                    g.p(86f, 0f).x, 0f, g.p(304f, 0f).x, 0f,
                    intArrayOf(Lux.Red.toArgb(), Lux.Amber.toArgb(), lerp(Lux.Amber, Lux.Green, 0.62f).toArgb(), Lux.Green.toArgb()),
                    floatArrayOf(0f, 0.42f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP,
                )
            }
        }
        val tl = g.p(CX - r, CY - r)
        canvas.nativeCanvas.drawArc(tl.x, tl.y, tl.x + 2 * r * s, tl.y + 2 * r * s, start, sweep, false, paint)
    }
}

private fun DrawScope.chevron(g: Geo, deg: Float, sgn: Float, on: Float, accent: Color) {
    val s = g.s
    val c = polar(g, PANEL_R, deg)
    val path = Path().apply {
        moveTo(c.x - 4.5f * s, c.y - sgn * 2f * s)
        lineTo(c.x, c.y + sgn * 2.6f * s)
        lineTo(c.x + 4.5f * s, c.y - sgn * 2f * s)
    }
    val off = if (Palette.isDark) Color(0xFF2C323B) else Palette.Hairline
    if (on > 0f) drawPath(path, accent.copy(alpha = 0.35f * on), style = Stroke(4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, lerp(off, accent, on), style = Stroke(1.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Text centred on x, placed by its baseline (SVG style) or centred on y. */
private fun DrawScope.text(
    measurer: TextMeasurer,
    value: String,
    at: Offset,
    style: TextStyle,
    baseline: Boolean = false,
    centreY: Boolean = false,
    alpha: Float = 1f,
) {
    if (value.isEmpty() || alpha <= 0.001f) return
    val layout = measurer.measure(value, style)
    val x = at.x - layout.size.width / 2f
    val y = when {
        baseline -> at.y - layout.firstBaseline
        centreY -> at.y - layout.size.height / 2f
        else -> at.y
    }
    drawText(layout, topLeft = Offset(x, y), alpha = alpha.coerceIn(0f, 1f))
}
