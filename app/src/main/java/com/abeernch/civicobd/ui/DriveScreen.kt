package com.abeernch.civicobd.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abeernch.civicobd.R
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.ColorScale
import com.abeernch.civicobd.core.Link
import com.abeernch.civicobd.core.LiveBus
import com.abeernch.civicobd.core.LiveState
import com.abeernch.civicobd.core.RouteContext
import com.abeernch.civicobd.core.RouteContexts
import com.abeernch.civicobd.core.Summary
import com.abeernch.civicobd.core.Tank
import com.abeernch.civicobd.core.TripSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** The main reading for the head-up display and the floating window. */
data class HeroReading(val value: String, val unit: String, val fuelCut: Boolean, val kmPerL: Double? = null) {
    /** Red to green by economy; plain text colour when standing or with no reading. */
    val color: Color get() = if (fuelCut || kmPerL != null) economyColor(kmPerL, fuelCut) else Palette.Value
}

fun heroReading(live: LiveState): HeroReading {
    val d = live.derived
    val running = live.link == Link.Live && (live.values[0x0C] ?: 0.0) > 0
    return when {
        !running -> HeroReading("--", "km/L", false)
        d.fuelCut -> HeroReading("0.0", "L/h  fuel cut", true)
        d.instantKmL != null -> HeroReading(fmt(d.instantKmL), "km/L  instant", false, d.instantKmL)
        else -> HeroReading(fmt(d.fuelLph), "L/h  standing", false)
    }
}

/** A drive that has just ended, for the goodbye on the Drive tab. */
data class CompletedDrive(val summary: TripSummary, val route: RouteContext?, val played: Boolean = false)

private const val CONTINUE_MS = 10 * 60_000L
private val SoftEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val WipeEase = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/**
 * The screen to glance at while driving. Driving or waiting: the cluster, or a live map with the key
 * figures, under one switch (a sideways swipe also moves between them). Stopped mid-drive: PARKED with the
 * countdown to the drive's end. Just ended: a quiet goodbye with the drive's figures.
 * Every view also lays out for a landscape mount.
 */
@Composable
fun DriveScreen(
    live: LiveState,
    today: Summary,
    tank: Tank? = null,
    settings: AppSettings,
    locationOk: Boolean = true,
    onHud: () -> Unit = {},
    completed: CompletedDrive? = null,
    goodbyePending: Boolean = false,
    onGoodbyePlayed: () -> Unit = {},
    onViewDrive: (Long) -> Unit = {},
    wakeAtMs: Long = 0L,
    onLocation: () -> Unit = {},
) {
    val paused = live.trip.active && live.tripPaused
    // The page's soft dark gradient is painted by the app shell, behind the title bar too.
    Box(Modifier.fillMaxSize()) {
        when {
            paused -> FadeIn("parked") { ParkedView(live, today, locationOk, onHud, onLocation) }
            !live.trip.active && completed != null -> Goodbye(completed, today, onGoodbyePlayed, onViewDrive)
            !live.trip.active && goodbyePending -> Unit // the drive's figures are loading: a moment of quiet
            else -> FadeIn("instruments") { Instruments(live, tank, settings, locationOk, onHud, wakeAtMs, onLocation) }
        }
    }
}

/** Fades its content in once when it first appears. */
@Composable
private fun FadeIn(key: String, content: @Composable () -> Unit) {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(450)) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }) { content() }
}

// ---------------------------------------------------------------- driving: the cluster and the map

@Composable
private fun Instruments(
    live: LiveState,
    tank: Tank?,
    settings: AppSettings,
    locationOk: Boolean,
    onHud: () -> Unit,
    wakeAtMs: Long,
    onLocation: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = settings.driveView.coerceIn(0, 1)) { 2 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.settledPage) { settings.driveView = pager.settledPage }
    fun go(page: Int) = scope.launch { pager.animateScrollToPage(page, animationSpec = spring(dampingRatio = 0.9f, stiffness = 400f)) }

    // The start-up sweep, if one is due: driven by the frame clock until it is over.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(wakeAtMs) {
        if (wakeAtMs <= 0L) return@LaunchedEffect
        now = System.currentTimeMillis()
        val lead = wakeAtMs - now
        if (lead > 0) delay(lead) // until then the cluster rests in its pre-sweep look
        while (System.currentTimeMillis() < wakeAtMs + 1_700) withFrameNanos { now = System.currentTimeMillis() }
        now = System.currentTimeMillis()
    }
    val wake = if (wakeAtMs > 0L && now < wakeAtMs + 1_700) (now - wakeAtMs) / 1000f else null
    val state = clusterState(live, tank, settings)
    val position = pager.currentPage + pager.currentPageOffsetFraction
    val switch: @Composable (Modifier) -> Unit = { m ->
        ViewSwitch(listOf("GAUGES", "MAP"), listOf(LuxIcon.Gauges, LuxIcon.Map), position, m) { go(it) }
    }
    val tiles: @Composable (Modifier) -> Unit = { m ->
        ControlTiles(live, locationOk, onHud, onLocation, onGpsOn = { go(1) }, modifier = m)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth <= maxHeight) {
            Column(Modifier.fillMaxSize()) {
                switch(Modifier.padding(horizontal = 20.dp).padding(top = 2.dp))
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    // On the map, sideways drags pan the map; the switch brings the gauges back.
                    userScrollEnabled = pager.settledPage == 0,
                    beyondViewportPageCount = 1,
                ) { page ->
                    if (page == 0) {
                        Column(Modifier.fillMaxSize()) {
                            DriveCluster(state, Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp), wake)
                            InfoStrip(live, Modifier.padding(horizontal = 20.dp))
                            Spacer(Modifier.height(22.dp))
                        }
                    } else {
                        MapPane(live, settings, locationOk, overlay = true, Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 18.dp))
                    }
                }
                tiles(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp))
            }
        } else {
            // Landscape: the cluster or the map on the left, the switch, figures and controls on the right.
            Row(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1.15f).fillMaxHeight(),
                    userScrollEnabled = pager.settledPage == 0,
                    beyondViewportPageCount = 1,
                ) { page ->
                    if (page == 0) DriveCluster(state, Modifier.fillMaxSize().padding(vertical = 6.dp), wake)
                    else MapPane(live, settings, locationOk, overlay = false, Modifier.fillMaxSize().padding(12.dp))
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(end = 20.dp, top = 10.dp, bottom = 12.dp)) {
                    switch(Modifier)
                    Spacer(Modifier.weight(1f))
                    if (pager.settledPage == 0) InfoStrip(live) else MapFigures(live, Modifier.fillMaxWidth())
                    Spacer(Modifier.weight(1f))
                    tiles(Modifier)
                }
            }
        }
    }
}

/** The cluster's figures from the live readings. */
private fun clusterState(live: LiveState, tank: Tank?, settings: AppSettings): ClusterState {
    val d = live.derived
    val connected = live.link == Link.Live
    val running = connected && (live.values[0x0C] ?: 0.0) > 0
    val fraction = tank?.leftL?.let { (it / settings.tankL).coerceIn(0.0, 1.0) }
    return ClusterState(
        speed = if (connected) live.values[0x0D] ?: 0.0 else 0.0,
        live = running,
        instant = if (running && !d.fuelCut) d.instantKmL else null,
        fuelCut = running && d.fuelCut,
        average = if (live.trip.active) live.trip.kmPerL else null,
        gear = if (running) d.gear else 0,
        shift = when {
            !running -> null
            d.lugging -> Shift.Down
            d.shiftUp -> Shift.Up
            else -> null
        },
        fuelFraction = fraction,
        rangeKm = tank?.rangeKm,
        label = centreLabel(live),
    )
}

private fun centreLabel(live: LiveState): String {
    val d = live.derived
    val running = live.link == Link.Live && (live.values[0x0C] ?: 0.0) > 0
    return when {
        running && d.fuelCut -> "FUEL CUT"
        running && d.instantKmL != null -> "km/L NOW"
        running -> "IDLE"
        live.link == Link.EngineOff -> "ENGINE OFF"
        live.link == Link.Searching || live.link == Link.Connecting -> "SEARCHING"
        else -> "NOT CONNECTED"
    }
}

/** Seconds as 24:18, or 1:05:32 past an hour. */
fun clockTime(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun InfoStrip(live: LiveState, modifier: Modifier = Modifier) {
    val t = live.trip
    Readouts(
        listOf(
            Readout("TRIP", fmt(if (t.active) t.distanceKm else 0.0, 1), "km"),
            Readout("TIME", clockTime(if (t.active) t.driveS + t.idleS else 0.0)),
            Readout("TRIP AVG", if (t.active) t.kmPerL?.let { fmt(it) } ?: "–" else "–", "km/L", Lux.GoldSoft),
        ),
        modifier,
    )
}

/** HUD, Bluetooth and GPS: what each is doing, and a tap to act on it. */
@Composable
private fun ControlTiles(
    live: LiveState,
    locationOk: Boolean,
    onHud: () -> Unit,
    onLocation: () -> Unit,
    onGpsOn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val (bt, btLed) = when (live.link) {
        Link.Live, Link.EngineOff -> "Connected" to Led.On
        Link.Searching, Link.Connecting -> "Searching" to Led.Blink
        Link.BluetoothOff -> "Off" to Led.Off
        Link.NoAdapter -> "No adapter" to Led.Off
        Link.Idle -> "Not connected" to Led.Off
    }
    val (gps, gpsLed) = when {
        live.locationOff -> "Off" to Led.Off
        !locationOk -> "No access" to Led.Off
        live.trip.active && !live.tripPaused && live.position == null -> "Searching" to Led.Blink
        else -> "On" to Led.On
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SlantTile(LuxIcon.Hud, "HUD", "Open", Led.Off, Modifier.weight(1f), description = "Open the head-up display", onClick = onHud)
        SlantTile(LuxIcon.Bluetooth, "BLUETOOTH", bt, btLed, Modifier.weight(1f), description = "Bluetooth: $bt") {
            com.abeernch.civicobd.service.ObdService.wake(context)
        }
        SlantTile(LuxIcon.Gps, "GPS", gps, gpsLed, Modifier.weight(1f), description = "GPS: $gps") {
            if (live.locationOff || !locationOk) onLocation() else onGpsOn()
        }
    }
}

// ---------------------------------------------------------------- the live map

@Composable
private fun MapPane(live: LiveState, settings: AppSettings, locationOk: Boolean, overlay: Boolean, modifier: Modifier) {
    val trail by LiveBus.trail.collectAsState()
    var follow by remember { mutableStateOf(true) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    // After the map is panned or zoomed by hand, it returns to the car 8 s after the last touch.
    LaunchedEffect(touchedAt) {
        if (touchedAt > 0) {
            delay(8_000)
            follow = true
        }
    }
    val scale = ColorScale.entries.firstOrNull { it.name == settings.routeScale } ?: ColorScale.Parula
    val tiles = MapTiles.entries.firstOrNull { it.name == settings.mapTiles } ?: MapTiles.Esri
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.clip(shape).border(1.dp, Lux.Line, shape)) {
        LiveMap(
            trail = trail,
            position = live.position,
            speedKmh = live.values[0x0D],
            scale = scale,
            tileKind = tiles,
            follow = follow,
            onTouched = { follow = false; touchedAt = System.currentTimeMillis() },
            modifier = Modifier.fillMaxSize(),
        )
        if (overlay) MapFigures(live, Modifier.align(Alignment.TopCenter).padding(10.dp).fillMaxWidth())
        val message = when {
            live.locationOff -> "Location is off. Tap GPS below to turn it on"
            !locationOk -> "Allow location to see the map"
            live.position == null && live.trip.active -> "Waiting for GPS"
            live.position == null -> "The map follows the car once a drive starts"
            else -> null
        }
        message?.let { MapNote(it, Modifier.align(if (overlay) Alignment.Center else Alignment.TopCenter)) }
        MapButton(LuxIcon.Gps, "Re-centre on the car", Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 22.dp)) {
            follow = true
            touchedAt = 0L
        }
        TripChip(live, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 26.dp))
        Text(
            tiles.credit,
            style = lux(8.5.sp, FontWeight.Normal, Lux.Dim),
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun MapNote(text: String, modifier: Modifier) {
    Text(
        text,
        style = lux(12.5.sp, FontWeight.Light, Lux.Txt).copy(textAlign = TextAlign.Center),
        modifier = modifier
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF050608).copy(alpha = 0.85f))
            .border(1.dp, Lux.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun TripChip(live: LiveState, modifier: Modifier) {
    val t = live.trip
    if (!t.active) return
    Text(
        "${fmt(t.distanceKm, 1)} km · ${clockTime(t.driveS + t.idleS)}",
        style = lux(12.sp, FontWeight.Light, Color(0xFFB5BBC5)),
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF050608).copy(alpha = 0.8f))
            .border(1.dp, Lux.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Speed, instant economy against the trip average, and the gear, in one dark card. */
@Composable
private fun MapFigures(live: LiveState, modifier: Modifier) {
    val d = live.derived
    val connected = live.link == Link.Live
    val running = connected && (live.values[0x0C] ?: 0.0) > 0
    val speed = if (connected) (live.values[0x0D] ?: 0.0).roundToInt() else 0
    val instant = if (running && !d.fuelCut) d.instantKmL else null
    val avg = if (live.trip.active) live.trip.kmPerL else null
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .clip(shape)
            .background(Color(0xFF050608).copy(alpha = 0.82f))
            .border(1.dp, Lux.Line, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.widthIn(min = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$speed", style = lux(46.sp, FontWeight.ExtraLight, if (running) Lux.Txt else Lux.Faint).copy(lineHeight = 46.sp), maxLines = 1)
            Text("km/h", style = lux(9.5.sp, FontWeight.Normal, Lux.Dim, 2.6.sp))
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.width(1.dp).fillMaxHeight().background(Lux.Line))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(
                    when {
                        running && d.fuelCut -> "cut"
                        instant != null -> fmt(instant)
                        else -> "–"
                    },
                    style = lux(24.sp, FontWeight.Normal, if (running && d.fuelCut) Lux.Cut else Lux.eco(instant)).copy(lineHeight = 26.sp),
                )
                Text(if (avg != null) "avg ${fmt(avg)}" else "", style = lux(12.sp, FontWeight.Normal, Lux.GoldSoft, 0.5.sp))
            }
            MiniBar(if (running && d.fuelCut) 30.0 else instant, avg, Modifier.padding(top = 8.dp).fillMaxWidth().height(14.dp))
            Text(centreLabel(live), style = lux(9.5.sp, FontWeight.Normal, Lux.Dim, 2.2.sp), modifier = Modifier.padding(top = 3.dp), maxLines = 1)
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.width(1.dp).fillMaxHeight().background(Lux.Line))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val gear = when {
                !running -> "N"
                d.gear == 0 -> "N"
                else -> d.gear.toString()
            }
            Text(
                gear,
                style = lux(24.sp, FontWeight.Medium, if (Palette.isDark) Color.White else Palette.Value),
                // A soft halo behind the lit gear, like the cluster's.
                modifier = Modifier.drawBehind {
                    if (Palette.isDark) {
                        val r = size.height * 0.8f
                        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), center = center, radius = r), r, center)
                    }
                },
            )
            val (label, col) = when {
                running && d.lugging -> "DOWN" to Lux.Amber
                running && d.shiftUp -> "SHIFT" to Lux.Gold
                else -> "GEAR" to Lux.Dim
            }
            Text(label, style = lux(8.5.sp, FontWeight.Normal, col, 2.2.sp), modifier = Modifier.padding(top = 5.dp), maxLines = 1)
        }
    }
}

/** A thin bar from 0 to 30 km/L: the instant value as a coloured fill with a white knob, the trip average as an accent tick. */
@Composable
private fun MiniBar(instant: Double?, average: Double?, modifier: Modifier) {
    val fill by animateFloatAsState(((instant ?: 0.0) / 30.0).coerceIn(0.0, 1.0).toFloat(), Motion.settle(), label = "fill")
    val mark by animateFloatAsState(((average ?: 0.0) / 30.0).coerceIn(0.0, 1.0).toFloat(), Motion.glide(), label = "mark")
    val red = Lux.Red
    val amber = Lux.Amber
    val green = Lux.Green
    val accent = Lux.Gold
    Canvas(modifier) {
        val u = 1.dp.toPx()
        val y = size.height / 2
        drawRoundRect(Color(0xFF1C2027), Offset(0f, y - u), Size(size.width, 2 * u), CornerRadius(u, u))
        if (fill > 0.005f) {
            drawRoundRect(
                Brush.horizontalGradient(0f to red, 0.45f to amber, 1f to green, startX = 0f, endX = size.width),
                Offset(0f, y - 1.5f * u), Size(size.width * fill, 3 * u), CornerRadius(2 * u, 2 * u),
            )
        }
        if (instant != null) drawCircle(Color(0xFFF4F5F7), 4 * u, Offset(size.width * fill, y))
        if (average != null) drawLine(accent, Offset(size.width * mark, 0f), Offset(size.width * mark, size.height), 2 * u, StrokeCap.Round)
    }
}

// ---------------------------------------------------------------- parked

@Composable
private fun ParkedView(live: LiveState, today: Summary, locationOk: Boolean, onHud: () -> Unit, onLocation: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000 - now % 1_000)
        }
    }
    val until = if (live.pausedUntilMs > 0) live.pausedUntilMs else now + CONTINUE_MS
    val left = (until - now).coerceIn(0L, CONTINUE_MS)
    val fraction = left / CONTINUE_MS.toFloat()
    val time = "%d:%02d".format(left / 60_000, left / 1_000 % 60)
    val t = live.trip
    val kml = t.kmPerL
    val figures: @Composable (Modifier) -> Unit = { m ->
        Readouts(
            listOf(
                Readout("SO FAR", fmt(t.distanceKm, 1), "km"),
                Readout("TIME", clockTime(t.driveS + t.idleS)),
                Readout("KM/L", kml?.let { fmt(it) } ?: "–", "", Lux.eco(kml)),
                Readout("COST", "Rs " + "%,d".format((t.fuelL * live.fuelPrice).roundToInt())),
            ),
            m, size = 22.sp,
        )
    }
    val word: @Composable () -> Unit = {
        Text("PARKED", style = lux(28.sp, FontWeight.ExtraLight, Lux.Txt, 8.sp), modifier = Modifier.padding(start = 8.dp))
    }
    val end: @Composable (Modifier) -> Unit = { m -> SlantWide("END DRIVE NOW", m) { LiveBus.requestEndDrive() } }
    val tiles: @Composable (Modifier) -> Unit = { m -> ControlTiles(live, locationOk, onHud, onLocation, onGpsOn = {}, modifier = m) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth <= maxHeight) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                word()
                Spacer(Modifier.height(24.dp))
                CountdownRing(fraction, time, Modifier.size(184.dp), 46.sp)
                Spacer(Modifier.weight(1f))
                figures(Modifier.padding(horizontal = 20.dp))
                TodayLine(today, Modifier.padding(top = 14.dp, start = 20.dp, end = 20.dp))
                Spacer(Modifier.weight(0.8f))
                end(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp))
                tiles(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    word()
                    Spacer(Modifier.height(14.dp))
                    CountdownRing(fraction, time, Modifier.size(150.dp), 38.sp)
                }
                Column(Modifier.weight(1.2f).fillMaxHeight().padding(end = 20.dp, top = 12.dp, bottom = 12.dp)) {
                    figures(Modifier)
                    TodayLine(today, Modifier.padding(top = 12.dp))
                    Spacer(Modifier.weight(1f))
                    end(Modifier.padding(bottom = 12.dp))
                    tiles(Modifier)
                }
            }
        }
    }
}

/** The time left to carry on this drive, as a ring that empties. */
@Composable
private fun CountdownRing(fraction: Float, time: String, modifier: Modifier, textSize: androidx.compose.ui.unit.TextUnit) {
    val f by animateFloatAsState(fraction, tween(1_000, easing = LinearEasing), label = "countdown")
    val accent = Lux.Gold
    val line = Lux.Line
    Box(modifier.semantics { contentDescription = "Parked: $time left to continue this drive" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 184f
            val r = 80f * u
            val tl = Offset(center.x - r, center.y - r)
            val sz = Size(2 * r, 2 * r)
            drawCircle(line, r, style = Stroke(1.5f * u))
            if (f > 0.002f) {
                drawArc(accent.copy(alpha = 0.16f), -90f, 360f * f, false, tl, sz, style = Stroke(7f * u, cap = StrokeCap.Round))
                drawArc(accent, -90f, 360f * f, false, tl, sz, style = Stroke(2.4f * u, cap = StrokeCap.Round))
            }
        }
        Text(time, style = lux(textSize, FontWeight.ExtraLight, Lux.Txt))
    }
}

/** "Today · 2 drives · 41.2 km · 12.9 km/L", the economy in the accent. */
@Composable
private fun TodayLine(today: Summary, modifier: Modifier = Modifier) {
    val soft = Lux.GoldSoft
    val text = buildAnnotatedString {
        append("Today · ${today.trips} drive${if (today.trips == 1) "" else "s"} · ${fmt(today.distanceKm, 1)} km")
        today.kmPerL?.let { k ->
            append(" · ")
            withStyle(SpanStyle(color = soft)) { append("${fmt(k)} km/L") }
        }
    }
    Text(text, style = lux(12.5.sp, FontWeight.Light, Lux.Sub, 0.3.sp).copy(textAlign = TextAlign.Center), modifier = modifier.fillMaxWidth())
}

// ---------------------------------------------------------------- the goodbye

@Composable
private fun Goodbye(c: CompletedDrive, today: Summary, onPlayed: () -> Unit, onViewDrive: (Long) -> Unit) {
    val trip = c.summary.trip
    // Decided once: the clip plays the first time this goodbye is seen; after that the car rests dark.
    val playing = remember(trip.id) { !c.played }
    var t by remember(trip.id) { mutableFloatStateOf(if (playing) 0f else 99f) }
    LaunchedEffect(trip.id) {
        if (!playing) return@LaunchedEffect
        onPlayed()
        val start = withFrameNanos { it }
        while (t < 7f) withFrameNanos { n -> t = (n - start) / 1e9f }
    }
    val k = c.summary.kmPerL
    val comparison = RouteContexts.comparison(k, c.route?.usualKmL, c.summary.averageKmL)
    val ended = SimpleDateFormat("EEE d MMM, HH:mm", Locale.US).format(Date(trip.endMs))
    val sub = (c.route?.let { "${it.name} · " } ?: "") + ended
    val hours = (trip.driveS + trip.idleS) / 3600.0
    val rate = if (hours > 0.01) trip.fuelL / hours else null

    val car: @Composable (Modifier) -> Unit = { m ->
        Box(m.fillMaxWidth().aspectRatio(2f)) {
            if (playing && t < 6.5f) ClipPlayer(R.raw.civic_end, Modifier.fillMaxSize(), alpha = span(t, 0.15f, 0.8f))
            else ClipStill(Modifier.fillMaxSize())
        }
    }
    val title: @Composable () -> Unit = {
        RevealTitle(
            "Drive complete",
            reveal = span(t, 2.6f, 4.3f, WipeEase),
            sheen = if (playing) span(t, 3.1f, 5.4f, LinearEasing) else 1f,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        )
        val h = span(t, 4.0f, 5.0f, SoftEase)
        Box(
            Modifier
                .width(200.dp)
                .height(1.dp)
                .graphicsLayer { scaleX = h; alpha = h }
                .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = if (Palette.isDark) 0.5f else 0.3f), Color.White.copy(alpha = 0f))))
        )
        Text(
            sub,
            style = lux(13.5.sp, FontWeight.Light, if (Palette.isDark) Color(0xFFA9AFB9) else Palette.Label, 1.sp).copy(textAlign = TextAlign.Center),
            modifier = Modifier.padding(top = 12.dp, start = 20.dp, end = 20.dp).fillMaxWidth().graphicsLayer { alpha = span(t, 4.5f, 5.2f) },
        )
    }
    val panelIn = span(t, 5.1f, 6.1f, SoftEase)
    val panel: @Composable (Modifier) -> Unit = { m ->
        Column(m.graphicsLayer { alpha = panelIn; translationY = (1f - panelIn) * 10.dp.toPx() }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("ECONOMY", style = lux(9.5.sp, FontWeight.Normal, Lux.Dim, 2.2.sp))
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                        Text(k?.let { fmt(it) } ?: "–", style = lux(48.sp, FontWeight.ExtraLight, Lux.eco(k)).copy(lineHeight = 50.sp))
                        Text(" km/L", style = lux(13.sp, FontWeight.Normal, Lux.Dim), modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
                comparison?.let { (text, good) ->
                    Text(
                        text,
                        style = lux(12.5.sp, FontWeight.Light, if (good == true) Lux.Green else if (good == false) Lux.Amber else Lux.Sub)
                            .copy(textAlign = TextAlign.End, lineHeight = 17.sp),
                        modifier = Modifier.widthIn(max = 160.dp).padding(bottom = 6.dp),
                    )
                }
            }
            Readouts(
                listOf(
                    Readout("DISTANCE", fmt(trip.distanceKm, 1), "km"),
                    Readout("TIME", clockTime(trip.driveS + trip.idleS)),
                    Readout("FUEL", fmt(trip.fuelL, 2), "L"),
                    Readout("COST", "Rs " + "%,d".format(trip.cost.roundToInt())),
                ),
                size = 21.sp,
            )
            Text(
                listOfNotNull(rate?.let { "Average fuel rate ${fmt(it)} L/h" }, "top speed ${trip.maxSpeed.roundToInt()} km/h")
                    .joinToString(" · ").replaceFirstChar { it.uppercase() },
                style = lux(12.sp, FontWeight.Light, Lux.Sub).copy(textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
    val lastIn = span(t, 5.8f, 6.5f)
    val view: @Composable (Modifier) -> Unit = { m ->
        SlantWide("VIEW DRIVE", m.graphicsLayer { alpha = lastIn }, trailing = LuxIcon.Chevron) { onViewDrive(trip.id) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        CloudBackdrop(span(t, 0.4f, 3.6f, LinearEasing), Modifier.fillMaxWidth().height(440.dp))
        if (maxWidth <= maxHeight) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(0.35f))
                car(Modifier)
                Spacer(Modifier.height(4.dp))
                title()
                panel(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp))
                TodayLine(today, Modifier.padding(top = 14.dp, start = 20.dp, end = 20.dp).graphicsLayer { alpha = lastIn })
                Spacer(Modifier.weight(1f))
                view(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    car(Modifier.padding(horizontal = 12.dp))
                    title()
                }
                Column(Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 20.dp, top = 8.dp, bottom = 12.dp)) {
                    panel(Modifier)
                    TodayLine(today, Modifier.padding(top = 12.dp).graphicsLayer { alpha = lastIn })
                    Spacer(Modifier.height(16.dp))
                    view(Modifier)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- the start: the car's lights come up, a greeting, then the cluster wakes

/**
 * Full-screen welcome, played when the app is opened for a drive: the car clip, a greeting, then a fade
 * into the Drive tab, where the cluster sweeps awake. A tap skips to the end.
 * [onWakeAt] gives the time the cluster's sweep should start; [onReveal] lets the app appear underneath.
 */
@Composable
fun DriveIntro(onReveal: () -> Unit, onWakeAt: (Long) -> Unit, onDone: () -> Unit) {
    var t by remember { mutableFloatStateOf(0f) }
    var skipBy by remember { mutableFloatStateOf(0f) }
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    LaunchedEffect(Unit) {
        onWakeAt(System.currentTimeMillis() + 7_600)
        val start = withFrameNanos { it }
        var revealed = false
        while (t < 7.4f) {
            withFrameNanos { n -> t = (n - start) / 1e9f + skipBy }
            if (!revealed && t >= 6.2f) {
                revealed = true
                onReveal()
            }
        }
        onDone()
    }
    val out = span(t, 6.6f, 7.4f)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - out }
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    if (t < 6.6f) {
                        skipBy += 6.6f - t
                        onWakeAt(System.currentTimeMillis() + 1_000)
                    }
                }
            }
            .semantics { contentDescription = "Welcome. Tap to skip" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f)
                    .graphicsLayer {
                        val k = 1f - 0.04f * out
                        scaleX = k
                        scaleY = k
                    }
            ) { ClipPlayer(R.raw.civic_start, Modifier.fillMaxSize()) }
            Spacer(Modifier.height(30.dp))
            val g = span(t, 4.4f, 5.4f, SoftEase)
            Text(greeting, style = lux(28.sp, FontWeight.ExtraLight, Color(0xFFECEEF1), (10f - 6f * g).sp), modifier = Modifier.graphicsLayer { alpha = g })
            Spacer(Modifier.height(12.dp))
            Text(
                "READY TO DRIVE",
                style = lux(10.5.sp, FontWeight.Normal, lerpSoft(), 4.sp),
                modifier = Modifier.offset(x = 2.dp).graphicsLayer { alpha = span(t, 5.0f, 5.6f) },
            )
        }
    }
}

/** The accent's soft tint, for use on black whatever the theme. */
private fun lerpSoft(): Color = androidx.compose.ui.graphics.lerp(Palette.Accent, Color.White, 0.36f)

// ---------------------------------------------------------------- kept for the Trips tab, HUD and floating window

/** Two large segments with a soft accent fill that slides along with the swipe. */
@Composable
fun SegmentSwitch(labels: List<String>, position: Float, onPick: (Int) -> Unit) {
    val seg = 100.dp
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .width(seg * labels.size)
            .height(46.dp)
            .clip(shape)
            .border(1.dp, Palette.Hairline, shape)
    ) {
        Box(
            Modifier
                .offset(x = seg * position.coerceIn(0f, 1f))
                .width(seg)
                .fillMaxHeight()
                .padding(4.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.AccentSoft)
        )
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                val on = position.roundToInt() == i
                Box(
                    Modifier.width(seg).fillMaxHeight().pressable { onPick(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = Type.body.copy(
                            color = if (on) Palette.Value else Palette.Label,
                            fontSize = Type.sz(16f),
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Shift light: a bar that fills as engine speed approaches the upshift point for the current
 * throttle, and turns accent when it is time to shift. "Shift down" replaces it when lugging.
 */
@Composable
fun ShiftCue(rpm: Double?, d: com.abeernch.civicobd.core.Derived, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 10.dp) {
    val target = d.shiftAtRpm
    val fill by animateFloatAsState(
        if (rpm != null && target != null) ((rpm - target * 0.6) / (target * 0.4)).toFloat().coerceIn(0f, 1f) else 0f,
        Motion.settle(), label = "shift",
    )
    val hot = d.shiftUp || d.lugging
    val color by androidx.compose.animation.animateColorAsState(
        when {
            d.lugging -> Palette.Caution
            d.shiftUp -> Palette.Accent
            else -> Palette.Value.copy(alpha = 0.55f)
        },
        Motion.fade(200), label = "shiftColor",
    )
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val r = size.height / 2
            drawRoundRect(Palette.Hairline, cornerRadius = CornerRadius(r, r))
            val w = if (d.lugging) size.width else size.width * fill
            if (w > 0f) drawRoundRect(color, size = Size(w, size.height), cornerRadius = CornerRadius(r, r))
        }
        Text(
            when {
                d.lugging -> "Shift down"
                d.shiftUp -> "Shift up"
                target != null -> "Shift at ${fmt(target, 0)} rpm"
                else -> " "
            },
            style = Type.label.copy(color = if (hot) color else Palette.Label),
        )
    }
}
