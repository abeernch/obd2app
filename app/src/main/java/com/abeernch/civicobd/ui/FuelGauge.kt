package com.abeernch.civicobd.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.Tank
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fuel level, as a needle dial or a digital bar, with the percentage, litres left and range.
 * Tap the gauge, or the Dial / Digital switch, to change style; the choice is remembered.
 * The level is counted from the last full tank logged in Stats > Fill-ups.
 */
@Composable
fun FuelGauge(tank: Tank?, settings: AppSettings) {
    var analog by remember { mutableStateOf(settings.fuelGaugeAnalog) }
    fun toggle(to: Boolean = !analog) { analog = to; settings.fuelGaugeAnalog = to }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fuel", style = Type.section, modifier = Modifier.weight(1f))
            listOf(true to "Dial", false to "Digital").forEach { (style, label) ->
                Text(
                    label,
                    style = Type.label.copy(color = if (analog == style) Palette.Accent else Palette.Label),
                    modifier = Modifier.pressable { toggle(style) }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
        Spacer(Modifier.height(10.dp))

        val left = tank?.leftL
        if (tank == null || left == null) {
            Text(
                "Log a full tank in Stats > Fill-ups, and the fuel level shows here.",
                style = Type.label,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            return@Column
        }
        val fraction = (left / settings.tankL).coerceIn(0.0, 1.0).toFloat()
        val level by animateFloatAsState(fraction, Motion.glide(), label = "fuel")
        val tint by animateColorAsState(
            when {
                fraction < 0.08f -> Palette.Warning
                fraction < 0.15f -> Palette.Caution
                else -> Palette.Value
            },
            Motion.fade(400), label = "fuelTint",
        )
        val percent = "${(fraction * 100).toInt()}"
        val detail = "${fmt(left, 0)} L of ${fmt(settings.tankL, 0)}" +
            (tank.rangeKm?.let { "  ·  ${fmt(it, 0)} km range" } ?: "")

        Column(Modifier.fillMaxWidth().pressable { toggle() }) {
            if (analog) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FuelDial(level, tint, Modifier.width(150.dp).height(96.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Percent(percent, tint)
                        Text(detail, style = Type.label)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Percent(percent, tint)
                    Spacer(Modifier.width(14.dp))
                    Text(detail, style = Type.label, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(10.dp))
                FuelBar(level, tint)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("E", style = Type.label)
                    Text("½", style = Type.label)
                    Text("F", style = Type.label)
                }
            }
        }
    }
}

@Composable
private fun Percent(value: String, tint: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        RollingNumber(value, Type.value.copy(color = tint))
        Text(" %", style = Type.label.copy(fontSize = Type.sz(18f)), modifier = Modifier.padding(bottom = 6.dp))
    }
}

/** A classic dial: E on the left, F on the right, a red mark at the bottom eighth, an accent needle. */
@Composable
private fun FuelDial(level: Float, tint: Color, modifier: Modifier) {
    val track = Palette.Hairline
    val ink = Palette.Label
    val needle = Palette.Accent
    val low = Palette.Warning
    val labelStyle = Type.label
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val sweep = 120f          // degrees from E to F
            val startAngle = 210f     // E sits at 210° (lower left), F at 330° (lower right)
            val stroke = 6.dp.toPx()
            val r = minOf(size.width / 2 / sin(Math.toRadians(sweep / 2.0)).toFloat(), size.height) - stroke
            val c = Offset(size.width / 2, r + stroke)
            val box = Size(r * 2, r * 2)
            val topLeft = Offset(c.x - r, c.y - r)
            drawArc(track, startAngle, sweep, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
            // Reserve zone, the first eighth of the dial.
            drawArc(low.copy(alpha = 0.7f), startAngle, sweep * 0.125f, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(tint.copy(alpha = 0.55f), startAngle, sweep * level, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
            // Ticks at E, quarter, half, three quarters and F.
            for (k in 0..4) {
                val a = Math.toRadians((startAngle + sweep * k / 4f).toDouble())
                val outer = Offset(c.x + (r - stroke * 1.4f) * cos(a).toFloat(), c.y + (r - stroke * 1.4f) * sin(a).toFloat())
                val inner = Offset(c.x + (r - stroke * 2.8f) * cos(a).toFloat(), c.y + (r - stroke * 2.8f) * sin(a).toFloat())
                drawLine(ink, inner, outer, 2.dp.toPx(), StrokeCap.Round)
            }
            val a = Math.toRadians((startAngle + sweep * level).toDouble())
            val tip = Offset(c.x + (r - stroke * 0.5f) * cos(a).toFloat(), c.y + (r - stroke * 0.5f) * sin(a).toFloat())
            drawLine(needle, c, tip, 3.5.dp.toPx(), StrokeCap.Round)
            drawCircle(needle, 5.dp.toPx(), c)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("E", style = labelStyle)
            Text("F", style = labelStyle)
        }
    }
}

/** Ten segments, lit up to the level; the last part of the lit segment fills partly. */
@Composable
private fun FuelBar(level: Float, tint: Color) {
    val off = Palette.Hairline
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val n = 10
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        for (i in 0 until n) {
            val x = i * (w + gap)
            drawRoundRect(off, Offset(x, 0f), Size(w, size.height), radius)
            val part = (level * n - i).coerceIn(0f, 1f)
            if (part > 0f) drawRoundRect(tint, Offset(x, 0f), Size(w * part, size.height), radius)
        }
    }
}
