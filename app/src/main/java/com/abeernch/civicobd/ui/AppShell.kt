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
import androidx.compose.foundation.border
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
    // The app is composed from the first frame, hidden under the splash, so it is ready
    // the moment the splash starts to leave. It then rises into place while the splash fades:
    // a crossfade, never a blank frame.
    var revealed by rememberSaveable { mutableStateOf(false) }
    val reveal by animateFloatAsState(if (revealed) 1f else 0f, Motion.fade(480), label = "reveal")

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        run {
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = reveal
                        translationY = (1f - reveal) * 32.dp.toPx()
                        val s = 0.97f + 0.03f * reveal
                        scaleX = s
                        scaleY = s
                    }
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
            Splash(onReveal = { revealed = true }, onFinished = { splashDone = true })
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
    // A slightly raised panel, separated from the screen by a hairline.
    Column(Modifier.fillMaxWidth().background(Palette.Surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 8.dp, bottom = 6.dp)) {
            val slot = maxWidth / Tab.entries.size
            val pillWidth = 52.dp
            val home = slot * selected.ordinal + (slot - pillWidth) / 2

            // Elastic indicator: the edge leading the move runs ahead, the trailing edge follows,
            // so the pill stretches in flight and settles back to its size on arrival.
            val leadFast = androidx.compose.animation.core.spring<Dp>(dampingRatio = 0.8f, stiffness = 900f)
            val trailSlow = androidx.compose.animation.core.spring<Dp>(dampingRatio = 0.85f, stiffness = 300f)
            var lastIndex by androidx.compose.runtime.remember { mutableStateOf(selected.ordinal) }
            val movingRight = selected.ordinal >= lastIndex
            androidx.compose.runtime.SideEffect { lastIndex = selected.ordinal }
            val left by animateDpAsState(home, if (movingRight) trailSlow else leadFast, label = "pillLeft")
            val right by animateDpAsState(home + pillWidth, if (movingRight) leadFast else trailSlow, label = "pillRight")

            Box(
                Modifier
                    .offset(x = left, y = 2.dp)
                    .width((right - left).coerceAtLeast(pillWidth / 2))
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Palette.AccentSoft)
                    .border(1.dp, Palette.Accent.copy(alpha = 0.55f), RoundedCornerShape(15.dp))
            )
            Row(Modifier.fillMaxWidth()) {
                Tab.entries.forEach { t ->
                    TabButton(t, t == selected, Modifier.weight(1f)) { onPick(t) }
                }
            }
        }
    }
}

@Composable
private fun TabButton(tab: Tab, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val iconColor by animateColorAsState(if (on) Palette.Accent else Palette.Label, Motion.fade(250), label = "tabIcon")
    val textColor by animateColorAsState(if (on) Palette.Value else Palette.Label, Motion.fade(250), label = "tabText")
    // The chosen icon rises a touch, like a key being lifted.
    val rise by animateFloatAsState(if (on) -1.5f else 0f, Motion.glide(), label = "rise")
    Column(
        modifier
            .height(58.dp)
            .pressable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .height(34.dp)
                .graphicsLayer { translationY = rise * density },
            contentAlignment = Alignment.Center,
        ) { tabIcon(tab, iconColor, 21.dp) }
        Spacer(Modifier.height(4.dp))
        Text(
            tab.label,
            style = Type.label.copy(
                color = textColor,
                fontSize = androidx.compose.ui.unit.TextUnit(11.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
            ),
        )
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
