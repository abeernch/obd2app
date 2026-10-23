package com.abeernch.civicobd.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.Link
import com.abeernch.civicobd.core.LiveState

enum class Tab(val label: String) { Drive("Drive"), Sensors("Sensors"), Stats("Stats"), Logs("Logs"), Settings("Settings") }

/** Header with the connection light, the current screen, and the bottom tab bar. */
@Composable
fun AppShell(live: LiveState, screen: @Composable (Tab) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Drive) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Header(live)
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "tab",
            ) { current -> screen(current) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Tab.entries.forEach { t ->
                TabButton(t, t == tab, Modifier.weight(1f)) { tab = t }
            }
        }
    }
}

@Composable
private fun Header(live: LiveState) {
    val color by animateColorAsState(
        when (live.link) {
            Link.Live -> Palette.Target
            Link.EngineOff -> Palette.Value
            Link.BluetoothOff, Link.NoAdapter -> Palette.Caution
            else -> Palette.Label
        },
        tween(400),
        label = "link",
    )
    val working = live.link == Link.Searching || live.link == Link.Connecting
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Civic", style = Type.title, modifier = Modifier.weight(1f))
        StatusDot(color, breathing = working)
        Spacer(Modifier.width(8.dp))
        Crossfade(live.status, animationSpec = tween(300), label = "status") { text ->
            Text(text, style = Type.label.copy(color = color), maxLines = 1)
        }
    }
}

@Composable
private fun TabButton(tab: Tab, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color by animateColorAsState(if (on) Palette.Value else Palette.Label, tween(200), label = "tab")
    Column(
        modifier
            .clickable(
                interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        tabIcon(tab, color, 22.dp)
        Spacer(Modifier.height(4.dp))
        Text(tab.label, style = Type.label.copy(color = color))
    }
}

@Composable
private fun tabIcon(tab: Tab, color: Color, size: Dp) = when (tab) {
    Tab.Drive -> IconGauge(color, size)
    Tab.Sensors -> IconList(color, size)
    Tab.Stats -> IconBars(color, size)
    Tab.Logs -> IconWave(color, size)
    Tab.Settings -> IconSliders(color, size)
}
