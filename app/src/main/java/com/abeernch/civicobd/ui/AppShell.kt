package com.abeernch.civicobd.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    pageLift: Boolean = true,
    locationOk: Boolean = true,
    onLocation: () -> Unit = {},
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
    val reveal by animateFloatAsState(if (revealed) 1f else 0f, Motion.fade(480), label = "reveal")
    androidx.compose.runtime.LaunchedEffect(splashDone) { if (splashDone) onSplashFinished() }

    // On Drive, a soft lift of dark grey behind the instruments, falling to black; it fades with the tab,
    // and gives way to true black where a screen brings its own light (the goodbye).
    val lux by animateFloatAsState(if (tab == Tab.Drive && pageLift) 1f else 0f, Motion.fade(350), label = "luxPage")
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
                if (!(landscape && tab == Tab.Drive)) Header(live, locationOk, onLocation)
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
private fun Header(live: LiveState, locationOk: Boolean, onLocation: () -> Unit) {
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
        // The title keeps its one line; a long status gives way instead, ending in "…".
        Text("Civic", style = Type.title, maxLines = 1, softWrap = false)
        AnimatedContent(
            targetState = live.status,
            transitionSpec = {
                (slideInVertically(Motion.settle()) { it } + fadeIn(Motion.fade(250))) togetherWith
                    (slideOutVertically(Motion.settle()) { -it } + fadeOut(Motion.fade(180)))
            },
            contentAlignment = Alignment.CenterEnd,
            label = "status",
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        ) { text ->
            val context = androidx.compose.ui.platform.LocalContext.current
            // Right-aligned against the light. Tapping the status switches Bluetooth on (if needed) and looks
            // for the adapter again.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    text, style = Type.label.copy(color = color), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.pressable { com.abeernch.civicobd.service.ObdService.wake(context) },
                )
            }
        }
        StatusDot(color, breathing = working, halo = live.link == Link.Live)
        LocationMark(live, locationOk, onLocation)
    }
}

/** What location is doing, as the GPS control on Drive shows it: off or not allowed, finding a fix, recording, on. */
private enum class Gps(val description: String) {
    Off("Location is off. Routes are not recorded. Tap to turn it on"),
    NotAllowed("Location is not allowed. Routes are not recorded. Tap to allow it"),
    Searching("Location on, finding GPS"),
    Recording("Location on, recording the route"),
    On("Location on"),
}

/**
 * Location as a small mark beside the connection light: struck through in amber when it is off or not
 * allowed (a tap then turns it on, or asks for it), breathing while a drive waits for its first fix, in
 * the accent colour while a route is being recorded, and quiet otherwise.
 */
@Composable
private fun LocationMark(live: LiveState, locationOk: Boolean, onLocation: () -> Unit) {
    val state = when {
        live.locationOff -> Gps.Off
        !locationOk -> Gps.NotAllowed
        live.trip.active && !live.tripPaused && live.position == null -> Gps.Searching
        live.trip.active && !live.tripPaused -> Gps.Recording
        else -> Gps.On
    }
    val off = state == Gps.Off || state == Gps.NotAllowed
    val color by animateColorAsState(
        when (state) {
            Gps.Off, Gps.NotAllowed -> Palette.Caution
            Gps.Recording -> Palette.Accent
            else -> Palette.Label
        },
        Motion.fade(450),
        label = "gps",
    )
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .semantics { contentDescription = state.description }
            .then(if (off) Modifier.pressable(onClick = onLocation) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (state == Gps.Searching) {
            // Only while it searches: an idle mark draws no frames.
            val breathe = androidx.compose.animation.core.rememberInfiniteTransition(label = "gpsBreath")
            val pulse by breathe.animateFloat(
                initialValue = 0.35f, targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(tween(1100), androidx.compose.animation.core.RepeatMode.Reverse),
                label = "gpsPulse",
            )
            Box(Modifier.graphicsLayer { alpha = pulse }) { LineGlyph(LuxIcon.Gps, color, 19.dp, 1.7f) }
        } else {
            LineGlyph(LuxIcon.Gps, color, 19.dp, 1.7f, struck = off)
        }
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
