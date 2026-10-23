package com.abeernch.civicobd.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
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
    Row(modifier) {
        text.forEachIndexed { index, char ->
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically(tween(220)) { -it / 2 } + fadeOut(tween(160))) using
                        SizeTransform(clip = true)
                },
                label = "digit$index",
            ) { c ->
                Text(c.toString(), style = style)
            }
        }
    }
}

/** Connection status light. Breathes while working, steady when settled. */
@Composable
fun StatusDot(color: Color, breathing: Boolean, size: Dp = 8.dp) {
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse = transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(
        Modifier
            .size(size)
            .alpha(if (breathing) pulse.value else 1f)
    ) {
        drawCircle(color)
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
