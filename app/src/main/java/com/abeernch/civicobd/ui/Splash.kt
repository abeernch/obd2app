package com.abeernch.civicobd.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * The start-up moment, played once per cold start (about 1.8 s):
 * the gauge ring draws itself, the needle blips up like an engine start and settles at idle,
 * the name tightens into place, then everything lifts away into the app.
 */
@Composable
fun Splash(onFinished: () -> Unit) {
    val ring = remember { Animatable(0f) }      // 0..1 of the arc drawn
    val needle = remember { Animatable(0f) }    // 0..1 of full scale
    val word = remember { Animatable(0f) }      // name fade and tracking
    val exit = remember { Animatable(0f) }      // 0..1 leaving

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { ring.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
            launch {
                needle.animateTo(0.82f, tween(520, delayMillis = 320, easing = LinearOutSlowInEasing))
                needle.animateTo(0.18f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
            }
            launch { word.animateTo(1f, tween(650, delayMillis = 450, easing = FastOutSlowInEasing)) }
        }
        exit.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .graphicsLayer {
                alpha = 1f - exit.value
                scaleX = 1f + 0.06f * exit.value
                scaleY = 1f + 0.06f * exit.value
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Canvas(Modifier.size(168.dp)) {
                val stroke = 5.dp.toPx()
                val inset = stroke * 2
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                val start = 150f
                val sweep = 240f

                // Track, then the orange arc tracing over it
                drawArc(
                    Palette.Hairline, start, sweep * ring.value, false, topLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    Palette.Accent, start, sweep * needle.value, false, topLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )

                // Tick marks every 30 degrees
                val c = center
                val rOuter = arcSize.width / 2 - stroke * 1.8f
                val rInner = rOuter - 8.dp.toPx()
                for (i in 0..8) {
                    val a = Math.toRadians((start + 30.0 * i))
                    val alpha = (ring.value * 9 - i).coerceIn(0f, 1f)
                    drawLine(
                        Palette.Label.copy(alpha = alpha),
                        Offset(c.x + rInner * cos(a).toFloat(), c.y + rInner * sin(a).toFloat()),
                        Offset(c.x + rOuter * cos(a).toFloat(), c.y + rOuter * sin(a).toFloat()),
                        1.5.dp.toPx(), StrokeCap.Round,
                    )
                }

                // Needle
                rotate(start + sweep * needle.value, c) {
                    drawLine(
                        Palette.Value.copy(alpha = ring.value),
                        c, Offset(c.x + rInner - 6.dp.toPx(), c.y),
                        3.dp.toPx(), StrokeCap.Round,
                    )
                }
                drawCircle(Palette.Accent.copy(alpha = ring.value), 6.dp.toPx(), c)
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "CIVIC",
                style = Type.title.copy(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (14f - 8f * word.value).sp,
                ),
                modifier = Modifier.graphicsLayer {
                    alpha = word.value
                    translationY = (1f - word.value) * 12.dp.toPx()
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "engine data",
                style = Type.label,
                modifier = Modifier.graphicsLayer { alpha = word.value * 0.9f },
            )
        }
    }
}
