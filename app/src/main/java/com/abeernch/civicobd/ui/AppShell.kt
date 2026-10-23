package com.abeernch.civicobd.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.Link
import com.abeernch.civicobd.core.LiveState

enum class Tab(val label: String) { Drive("Drive"), Sensors("Sensors"), Stats("Stats"), Logs("Logs"), Settings("Settings") }

/**
 * Splash on a cold start, then the app: header with the connection light,
 * the current screen, and the bottom tab bar with a sliding orange indicator.
 */
@Composable
fun AppShell(live: LiveState, screen: @Composable (Tab) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Drive) }
    var splashDone by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        // The app sits underneath and rises into place as the splash fades.
        AnimatedVisibility(
            visible = splashDone,
            enter = fadeIn(Motion.fade(500)) + slideInVertically(Motion.settle()) { it / 12 } +
                scaleIn(Motion.settle(), initialScale = 0.98f),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Header(live)
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            // Screens slide in the direction of the tab you moved to.
                            val forward = targetState.ordinal > initialState.ordinal
                            val dir = if (forward) 1 else -1
                            (slideInHorizontally(Motion.settle()) { w -> dir * w / 5 } + fadeIn(Motion.fade(260))) togetherWith
                                (slideOutHorizontally(Motion.settle()) { w -> -dir * w / 5 } + fadeOut(Motion.fade(180)))
                        },
                        label = "tab",
                    ) { current -> screen(current) }
                }
                TabBar(tab) { tab = it }
            }
        }
        if (!splashDone) {
            Splash(onFinished = { splashDone = true })
        }
    }
}

@Composable
private fun Header(live: LiveState) {
    val color by animateColorAsState(
        when (live.link) {
            Link.Live -> Palette.Accent
            Link.EngineOff -> Palette.Value
            Link.BluetoothOff, Link.NoAdapter -> Palette.Caution
            else -> Palette.Label
        },
        Motion.fade(450),
        label = "link",
    )
    val working = live.link == Link.Searching || live.link == Link.Connecting
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Civic", style = Type.title, modifier = Modifier.weight(1f))
        AnimatedContent(
            targetState = live.status,
            transitionSpec = {
                (slideInVertically(Motion.settle()) { it } + fadeIn(Motion.fade(250))) togetherWith
                    (slideOutVertically(Motion.settle()) { -it } + fadeOut(Motion.fade(180)))
            },
            label = "status",
        ) { text ->
            Text(text, style = Type.label.copy(color = color), maxLines = 1)
        }
        StatusDot(color, breathing = working, halo = live.link == Link.Live)
    }
}

@Composable
private fun TabBar(selected: Tab, onPick: (Tab) -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        val slot = maxWidth / Tab.entries.size
        // The pill glides to the chosen tab with a slight overshoot.
        val x by animateDpAsState(slot * selected.ordinal, Motion.glide(), label = "pill")
        Box(
            Modifier
                .offset(x = x + 10.dp)
                .width(slot - 20.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.AccentSoft)
        )
        Row(Modifier.fillMaxWidth()) {
            Tab.entries.forEach { t ->
                TabButton(t, t == selected, Modifier.weight(1f)) { onPick(t) }
            }
        }
    }
}

@Composable
private fun TabButton(tab: Tab, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color by animateColorAsState(if (on) Palette.Accent else Palette.Label, Motion.fade(250), label = "tab")
    val lift by animateFloatAsState(if (on) 1.08f else 1f, Motion.glide(), label = "lift")
    Column(
        modifier
            .height(56.dp)
            .pressable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Box(Modifier.graphicsLayer { scaleX = lift; scaleY = lift }) { tabIcon(tab, color, 22.dp) }
        Spacer(Modifier.height(4.dp))
        Text(tab.label, style = Type.label.copy(color = if (on) Palette.Value else Palette.Label))
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
