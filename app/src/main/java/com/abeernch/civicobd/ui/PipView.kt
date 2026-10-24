package com.abeernch.civicobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abeernch.civicobd.core.Link
import com.abeernch.civicobd.core.LiveState

/**
 * The small floating window shown over other apps (Google Maps, Waze) during a trip.
 * Instant economy large on the left, speed and trip average on the right.
 * Sizes follow the window width, since the window is far smaller than the phone screen.
 */
@Composable
fun PipView(live: LiveState) {
    val hero = heroReading(live)
    val running = live.link == Link.Live && (live.values[0x0C] ?: 0.0) > 0
    val d = live.derived
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val u = (maxWidth.value / 220f).coerceIn(0.7f, 2f) // 1.0 at a typical 220 dp wide window
        fun num(size: Float) = TextStyle(
            fontFamily = Numeric, fontSize = (size * u).sp, lineHeight = (size * u * 1.02f).sp,
            fontWeight = FontWeight.Bold, color = Palette.Value, fontFeatureSettings = "tnum",
        )
        val small = TextStyle(fontFamily = Sans, fontSize = (11f * u).sp, color = Palette.Label)
        val shiftColor = when {
            d.lugging -> Palette.Caution
            d.shiftUp -> Palette.Accent
            else -> null
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1.3f)) {
                    Text(
                        if (hero.value == "--") "–" else hero.value,
                        style = num(48f).copy(color = if (hero.fuelCut) Palette.Accent else Palette.Value),
                        maxLines = 1,
                    )
                    Text(hero.unit, style = small, maxLines = 1)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(if (running) fmt(live.values[0x0D], 0) else "–", style = num(28f), maxLines = 1)
                    Text("km/h", style = small)
                    Spacer(Modifier.height((4 * u).dp))
                    Text(fmt(live.trip.kmPerL), style = num(20f).copy(color = Palette.Accent), maxLines = 1)
                    Text("km/L trip", style = small, maxLines = 1)
                }
            }
            if (shiftColor != null) {
                Spacer(Modifier.height((4 * u).dp))
                Text(
                    if (d.lugging) "Shift down" else "Shift up",
                    style = small.copy(color = shiftColor, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}
