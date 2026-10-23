package com.abeernch.civicobd.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.key
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text

/**
 * A number whose digits roll to the new value, one character at a time.
 * Only the characters that change move, so the eye catches the change without distraction.
 */
@Composable
fun RollingNumber(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    // Pad from the right so the units digit always keeps the same slot.
    Row(modifier) {
        text.forEachIndexed { index, char ->
            key(text.length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        // A rising digit rolls up from below, a falling one drops from above.
                        val up = targetState > initialState
                        val slide = spring(
                            dampingRatio = 0.85f,
                            stiffness = Spring.StiffnessMediumLow,
                            visibilityThreshold = IntOffset.VisibilityThreshold,
                        )
                        (slideInVertically(slide) { h -> if (up) h else -h } + fadeIn(tween(260))) togetherWith
                            (slideOutVertically(slide) { h -> if (up) -h else h } + fadeOut(tween(180))) using
                            SizeTransform(clip = true)
                    },
                    label = "digit",
                ) { c ->
                    Text(c.toString(), style = style)
                }
            }
        }
    }
}

/**
 * Connection status light. Breathes slowly while searching.
 * When [halo] is on (connected and live), a soft ring ripples out from it every two seconds.
 */
@Composable
fun StatusDot(color: Color, breathing: Boolean, size: Dp = 8.dp, halo: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse = transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val ripple = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearOutSlowInEasing)),
        label = "ripple",
    )
    Canvas(Modifier.size(size * 3)) {
        val r = size.toPx() / 2
        if (halo) {
            drawCircle(color.copy(alpha = 0.45f * (1f - ripple.value)), radius = r + r * 2f * ripple.value)
        }
        drawCircle(color.copy(alpha = if (breathing) pulse.value else 1f), radius = r)
    }
}

// ---- Icons: thin single-weight strokes on a 24-unit grid ----

@Composable
fun LineIcon(
    color: Color,
    size: Dp = 20.dp,
    draw: DrawScope.(stroke: Stroke, unit: Float) -> Unit,
) {
    Canvas(Modifier.size(size)) {
        val unit = this.size.width / 24f
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        draw(stroke, unit)
    }
}

private fun path(unit: Float, vararg points: Pair<Float, Float>): Path = Path().apply {
    points.forEachIndexed { i, (x, y) ->
        if (i == 0) moveTo(x * unit, y * unit) else lineTo(x * unit, y * unit)
    }
}

@Composable
fun IconBluetooth(color: Color, size: Dp = 20.dp) = LineIcon(color, size) { stroke, u ->
    drawPath(path(u, 7f to 7f, 17f to 17f, 12f to 22f, 12f to 2f, 17f to 7f, 7f to 17f), color, style = stroke)
}

@Composable
fun IconShare(color: Color, size: Dp = 20.dp) = LineIcon(color, size) { stroke, u ->
    drawPath(path(u, 6f to 11f, 6f to 20f, 18f to 20f, 18f to 11f), color, style = stroke)
    drawPath(path(u, 12f to 15f, 12f to 3f), color, style = stroke)
    drawPath(path(u, 8f to 7f, 12f to 3f, 16f to 7f), color, style = stroke)
}

@Composable
fun IconGauge(color: Color, size: Dp = 20.dp) = LineIcon(color, size) { stroke, u ->
    drawArc(
        color = color, startAngle = 150f, sweepAngle = 240f, useCenter = false,
        topLeft = Offset(3f * u, 4f * u),
        size = androidx.compose.ui.geometry.Size(18f * u, 18f * u),
        style = stroke,
    )
    drawLine(color, Offset(12f * u, 13f * u), Offset(16.5f * u, 8.5f * u), stroke.width, StrokeCap.Round)
}

@Composable
fun IconList(color: Color, size: Dp = 20.dp) = LineIcon(color, size) { stroke, u ->
    for (y in listOf(6f, 12f, 18f)) {
        drawLine(color, Offset(4f * u, y * u), Offset(20f * u, y * u), stroke.width, StrokeCap.Round)
    }
}

@Composable
fun IconBars(color: Color, size: Dp = 20.dp) = LineIcon(color, size) { stroke, u ->
    drawLine(color, Offset(6f * u, 20f * u), Offset(6f * u, 12f * u), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(12f * u, 20f * u), Offset(12f * u, 5f * u), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(18f * u, 20f * u), Offset(18f * u, 9f * u), stroke.width, StrokeCap.Round)
}

@Composable
fun IconSliders(color: Color, size: Dp = 20.dp) = LineIcon(color, size) { stroke, u ->
    drawLine(color, Offset(4f * u, 8f * u), Offset(20f * u, 8f * u), stroke.width, StrokeCap.Round)
    drawLine(color, Offset(4f * u, 16f * u), Offset(20f * u, 16f * u), stroke.width, StrokeCap.Round)
    for (knob in listOf(Offset(9f * u, 8f * u), Offset(15f * u, 16f * u))) {
        drawCircle(Palette.Background, 2.5f * u, knob) // hides the line inside the knob
        drawCircle(color, 2.5f * u, knob, style = stroke)
    }
}

@Composable
fun IconWave(color: Color, size: Dp = 20.dp) = LineIcon(color, size) { stroke, u ->
    val p = Path().apply {
        moveTo(3f * u, 15f * u)
        lineTo(7f * u, 9f * u)
        lineTo(11f * u, 14f * u)
        lineTo(15f * u, 6f * u)
        lineTo(21f * u, 12f * u)
    }
    drawPath(p, color, style = stroke)
    drawLine(color, Offset(3f * u, 20f * u), Offset(21f * u, 20f * u), stroke.width, StrokeCap.Round)
}

@Composable
fun IconPower(color: Color, size: Dp = 20.dp) = LineIcon(color, size) { stroke, u ->
    drawArc(
        color = color,
        startAngle = -60f,
        sweepAngle = 300f,
        useCenter = false,
        topLeft = Offset(5f * u, 5f * u),
        size = androidx.compose.ui.geometry.Size(14f * u, 14f * u),
        style = stroke,
    )
    drawLine(color, Offset(12f * u, 3f * u), Offset(12f * u, 11f * u), stroke.width, StrokeCap.Round)
}
