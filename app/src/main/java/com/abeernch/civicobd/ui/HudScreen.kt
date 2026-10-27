package com.abeernch.civicobd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.Link
import com.abeernch.civicobd.core.LiveState

/**
 * Head-up display: the phone lies on the dashboard and its reflection shows in the windshield.
 * Pure black with bright figures, so only the numbers reflect. Mirror flips the picture so the
 * reflection reads the right way round. Tap the top line to toggle mirror; back to leave.
 */
@Composable
fun HudScreen(live: LiveState, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    var mirror by rememberSaveable { mutableStateOf(true) }
    val d = live.derived
    val speed = live.values[0x0D]
    val rpm = live.values[0x0C]
    val running = live.link == Link.Live && (rpm ?: 0.0) > 0
    val bright = Color(0xFFFFFFFF)
    val accent = Palette.Accent

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = if (mirror) -1f else 1f }
                .padding(horizontal = 28.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            ShiftCue(rpm, d, height = 18.dp)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (running) fmt(speed, 0) else "–",
                    style = Type.hero.copy(
                        color = if (running) bright else Color(0xFF555555),
                        fontSize = Type.sz(190f), lineHeight = Type.sz(190f),
                    ),
                )
                Text("km/h", style = Type.label.copy(color = Color(0xFFBDBDBD), fontSize = Type.sz(22f)))
                // Trip distance, just under the speed and smaller, so the speed still leads.
                Spacer(Modifier.height(18.dp))
                Text(
                    fmt(live.trip.distanceKm, 1),
                    style = Type.hero.copy(color = Color(0xFFE6E6E6), fontSize = Type.sz(96f), lineHeight = Type.sz(96f)),
                )
                Text("km this trip", style = Type.label.copy(color = Color(0xFFBDBDBD), fontSize = Type.sz(20f)))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HudFigure(if (!running) "–" else if (d.gear == 0) "N" else d.gear.toString(), "gear", bright)
                HudFigure(
                    when {
                        !running -> "–"
                        d.fuelCut -> "cut"
                        else -> fmt(d.instantKmL)
                    },
                    // The HUD is always black, so the dark-theme shades are used.
                    "km/L", if (running && (d.fuelCut || d.instantKmL != null)) economyColor(d.instantKmL, d.fuelCut, dark = true) else bright,
                )
                HudFigure(fmt(live.trip.kmPerL), "trip", bright)
            }
        }
        // Controls stay readable (not mirrored) for the person setting it up.
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Text(
                if (mirror) "Mirror on" else "Mirror off",
                style = Type.label.copy(color = Color(0xFF6A6A70)),
                modifier = Modifier.pressable { mirror = !mirror }.padding(8.dp),
            )
            Text(
                "Close",
                style = Type.label.copy(color = Color(0xFF6A6A70)),
                modifier = Modifier.pressable(onClick = onExit).padding(8.dp),
            )
        }
    }
}

@Composable
private fun HudFigure(value: String, unit: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = Type.value.copy(color = color, fontSize = Type.sz(64f), lineHeight = Type.sz(66f), fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(2.dp))
        Text(unit, style = Type.label.copy(color = Color(0xFFBDBDBD), fontSize = Type.sz(18f)))
    }
}
