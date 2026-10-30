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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.Link
import com.abeernch.civicobd.core.LiveState

enum class Tab(val label: String) { Drive("Drive"), Trips("Trips"), Stats("Stats"), Car("Car"), Settings("Settings") }

/**
 * Splash on a cold start, then the app: header with the connection light,
 * the current screen, and the bottom tab bar with a sliding orange indicator.
 */
@Composable
fun AppShell(
    live: LiveState,
    driveJump: Int = 0,
    jump: Pair<Tab, Int>? = null,
    hold: Boolean = false,
    onSplashFinished: () -> Unit = {},
    screen: @Composable (Tab) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Drive) }
    androidx.compose.runtime.LaunchedEffect(driveJump) { if (driveJump > 0) tab = Tab.Drive }
    // A notification opened the app on a particular tab (the number makes each request unique).
    androidx.compose.runtime.LaunchedEffect(jump) { jump?.let { tab = it.first } }
    var splashDone by rememberSaveable { mutableStateOf(false) }
    // The app is composed from the first frame, hidden under the splash, so it is ready
    // the moment the splash starts to leave. It then rises into place while the splash fades:
    // a crossfade, never a blank frame.
    var revealed by rememberSaveable { mutableStateOf(false) }
    // While the drive welcome plays over the app, the app waits out of sight, then rises in under it.
    val reveal by animateFloatAsState(if (revealed && !hold) 1f else 0f, Motion.fade(480), label = "reveal")
    androidx.compose.runtime.LaunchedEffect(splashDone) { if (splashDone) onSplashFinished() }

    // On Drive, a soft lift of dark grey behind the instruments, falling to black; it fades with the tab.
    val lux by animateFloatAsState(if (tab == Tab.Drive) 1f else 0f, Motion.fade(350), label = "luxPage")
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .drawBehind { with(Lux) { drawLuxPage(lux) } }
    ) {
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
                // In landscape the Drive screen needs every bit of height, so the title bar steps aside there.
                val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                if (!(landscape && tab == Tab.Drive)) Header(live)
                // On Drive the GPS control shows the same thing, so the strip is not repeated there.
                if (live.locationOff && tab != Tab.Drive) LocationStrip()
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

/**
 * One line under the title while the phone's location switch is off. Apps can't switch location on
 * themselves, so "Turn on" opens the switch in Settings, and the app offers to turn it off after the drive.
 */
@Composable
private fun LocationStrip() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable {
                com.abeernch.civicobd.core.AppSettings(context).locOnByApp = true
                try {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {
                }
            }
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Location is off. Routes won't be recorded.", style = Type.label.copy(color = Palette.Caution), modifier = Modifier.weight(1f))
        Text("Turn on", style = Type.label.copy(color = Palette.Accent, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
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
            val context = androidx.compose.ui.platform.LocalContext.current
            // Tapping the status switches Bluetooth on (if needed) and looks for the adapter again.
            Text(
                text, style = Type.label.copy(color = color), maxLines = 1,
                modifier = Modifier.pressable { com.abeernch.civicobd.service.ObdService.wake(context) },
            )
        }
        StatusDot(color, breathing = working, halo = live.link == Link.Live)
    }
}

@Composable
private fun TabBar(selected: Tab, onPick: (Tab) -> Unit) {
    // A near-black panel under a hairline. A short accent bar sits over the chosen tab and glides
    // to the next one: its leading end runs ahead and the trailing end follows, then it settles.
    val dark = Palette.isDark
    Column(Modifier.fillMaxWidth().background(if (dark) Color(0xFF050608) else Palette.Surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (dark) Color(0xFF14171C) else Palette.Hairline))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 6.dp)) {
            val slot = maxWidth / Tab.entries.size
            val barWidth = 16.dp
            val home = slot * selected.ordinal + (slot - barWidth) / 2
            val leadFast = androidx.compose.animation.core.spring<Dp>(dampingRatio = 0.8f, stiffness = 900f)
            val trailSlow = androidx.compose.animation.core.spring<Dp>(dampingRatio = 0.85f, stiffness = 300f)
            val previous = androidx.compose.runtime.remember { intArrayOf(selected.ordinal) }
            val movingRight = androidx.compose.runtime.remember(selected) {
                val right = selected.ordinal >= previous[0]
                previous[0] = selected.ordinal
                right
            }
            val left by animateDpAsState(home, if (movingRight) trailSlow else leadFast, label = "barLeft")
            val right by animateDpAsState(home + barWidth, if (movingRight) leadFast else trailSlow, label = "barRight")
            Box(
                Modifier
                    .offset(x = left)
                    .width((right - left).coerceAtLeast(barWidth / 2))
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Palette.Accent)
            )
            Row(Modifier.fillMaxWidth().padding(top = 7.dp)) {
                Tab.entries.forEach { t ->
                    TabButton(t, t == selected, Modifier.weight(1f)) { onPick(t) }
                }
            }
        }
    }
}

@Composable
private fun TabButton(tab: Tab, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val rest = if (Palette.isDark) Color(0xFF7D8592) else Palette.Label
    val iconColor by animateColorAsState(if (on) Palette.Accent else rest, Motion.fade(250), label = "tabIcon")
    val textColor by animateColorAsState(if (on) Palette.Value else rest, Motion.fade(250), label = "tabText")
    Column(
        modifier
            .height(54.dp)
            .pressable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) { tabIcon(tab, iconColor, 20.dp) }
        Spacer(Modifier.height(3.dp))
        Text(
            tab.label,
            style = Type.label.copy(
                color = textColor,
                fontSize = Type.sz(12f),
                letterSpacing = androidx.compose.ui.unit.TextUnit(0.4f, androidx.compose.ui.unit.TextUnitType.Sp),
                fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
            ),
        )
    }
}

@Composable
private fun tabIcon(tab: Tab, color: Color, size: Dp) = when (tab) {
    Tab.Drive -> IconGauge(color, size)
    Tab.Trips -> IconRoute(color, size)
    Tab.Stats -> IconBars(color, size)
    Tab.Car -> IconCar(color, size)
    Tab.Settings -> IconSliders(color, size)
}
