package com.abeernch.civicobd.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.Tank
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small fuel indicator that sits beside the main number on the Drive screen:
 * a mini dial or a vertical bar, with the percentage and range under it.
 * Tap it to switch between the two; the choice is remembered.
 * The level counts down from the last full tank logged in Stats > Fill-ups.
 */
@Composable
fun FuelIndicator(tank: Tank?, settings: AppSettings, modifier: Modifier = Modifier) {
    var analog by remember { mutableStateOf(settings.fuelGaugeAnalog) }
    val left = tank?.leftL
    val fraction = left?.let { (it / settings.tankL).coerceIn(0.0, 1.0).toFloat() }
    val level by animateFloatAsState(fraction ?: 0f, Motion.glide(), label = "fuel")
    val tint by animateColorAsState(
        when {
            fraction == null -> Palette.Label
            fraction < 0.08f -> Palette.Warning
            fraction < 0.15f -> Palette.Caution
            else -> Palette.Accent
        },
        Motion.fade(400), label = "fuelTint",
    )

    Column(
        modifier
            .pressable { analog = !analog; settings.fuelGaugeAnalog = analog }
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (analog) MiniDial(level, tint, fraction != null, Modifier.width(72.dp).height(48.dp))
        else VerticalBar(level, tint, fraction != null, Modifier.width(16.dp).height(72.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            fraction?.let { "${(it * 100).toInt()}%" } ?: "–%",
            style = Type.body.copy(color = if (fraction == null) Palette.Label else tint, fontSize = Type.sz(18f)),
        )
        Text(
            when {
                fraction == null -> "log a fill"
                tank.rangeKm != null -> "${fmt(tank.rangeKm, 0)} km"
                else -> "${fmt(left, 0)} L"
            },
            style = Type.label.copy(fontSize = Type.sz(12f)),
            maxLines = 1,
        )
    }
}

/** A small arc from E to F with a needle. */
@Composable
private fun MiniDial(level: Float, tint: Color, known: Boolean, modifier: Modifier) {
    val track = Palette.Hairline
    val low = Palette.Warning
    Canvas(modifier) {
        val sweep = 140f
        val start = 200f
        val stroke = 4.dp.toPx()
        val r = minOf(size.width / 2 - stroke, size.height - stroke * 1.5f)
        val c = Offset(size.width / 2, size.height - stroke / 2)
        val box = Size(r * 2, r * 2)
        val topLeft = Offset(c.x - r, c.y - r)
        drawArc(track, start, sweep, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(low.copy(alpha = 0.7f), start, sweep * 0.125f, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
        if (!known) return@Canvas
        drawArc(tint.copy(alpha = 0.5f), start, sweep * level, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
        val a = Math.toRadians((start + sweep * level).toDouble())
        val tip = Offset(c.x + (r - stroke) * cos(a).toFloat(), c.y + (r - stroke) * sin(a).toFloat())
        drawLine(tint, c, tip, 2.5.dp.toPx(), StrokeCap.Round)
        drawCircle(tint, 3.5.dp.toPx(), c)
    }
}

/** A slim upright bar that fills from the bottom, with a reserve mark at one eighth. */
@Composable
private fun VerticalBar(level: Float, tint: Color, known: Boolean, modifier: Modifier) {
    val track = Palette.Hairline
    val low = Palette.Warning
    Canvas(modifier) {
        val radius = CornerRadius(size.width / 2, size.width / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (known && level > 0f) {
            val h = size.height * level
            drawRoundRect(tint, Offset(0f, size.height - h), Size(size.width, h), radius)
        }
        val y = size.height * (1 - 0.125f)
        drawLine(low, Offset(-2.dp.toPx(), y), Offset(size.width + 2.dp.toPx(), y), 1.5.dp.toPx())
    }
}
