package com.abeernch.civicobd.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.ColorScale
import com.abeernch.civicobd.core.Link
import com.abeernch.civicobd.core.LiveBus
import com.abeernch.civicobd.core.LiveState
import com.abeernch.civicobd.core.Summary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The main reading: km/L while moving, L/h while standing, "fuel cut" while coasting in gear. */
data class HeroReading(val value: String, val unit: String, val fuelCut: Boolean)

fun heroReading(live: LiveState): HeroReading {
    val d = live.derived
    val running = live.link == Link.Live && (live.values[0x0C] ?: 0.0) > 0
    return when {
        !running -> HeroReading("--", "km/L", false)
        d.fuelCut -> HeroReading("0.0", "L/h  fuel cut", true)
        d.instantKmL != null -> HeroReading(fmt(d.instantKmL), "km/L  instant", false)
        else -> HeroReading(fmt(d.fuelLph), "L/h  standing", false)
    }
}

/**
 * The screen to glance at while driving, in two views: gauges, and a live map with the
 * key numbers kept underneath. A switch at the top and a sideways swipe move between them,
 * and the last one used opens next time. Both views also lay out for a landscape mount.
 */
@Composable
fun DriveScreen(
    live: LiveState,
    today: Summary,
    tank: com.abeernch.civicobd.core.Tank? = null,
    settings: AppSettings,
    locationOk: Boolean = true,
    onHud: () -> Unit = {},
    lastTrip: com.abeernch.civicobd.core.TripSummary? = null,
) {
    val pager = rememberPagerState(initialPage = settings.driveView.coerceIn(0, 1)) { 2 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.settledPage) { settings.driveView = pager.settledPage }
    fun go(page: Int) = scope.launch {
        pager.animateScrollToPage(page, animationSpec = spring(dampingRatio = 0.9f, stiffness = 400f))
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentSwitch(listOf("Gauges", "Map"), pager.currentPage + pager.currentPageOffsetFraction) { go(it) }
            Spacer(Modifier.weight(1f))
            Text(
                "HUD", style = Type.label.copy(color = Palette.Accent, fontSize = Type.sz(16f)),
                modifier = Modifier.pressable(onClick = onHud).padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().weight(1f),
            // On the map, sideways drags pan the map; the switch, or a swipe on the numbers, comes back.
            userScrollEnabled = pager.settledPage == 0,
            beyondViewportPageCount = 1,
        ) { page ->
            if (page == 0) Gauges(live, today, tank, settings, lastTrip)
            else DriveMap(live, settings, locationOk, onSwipeBack = { go(0) })
        }
    }
}

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

// ---- Gauges ----

@Composable
private fun Gauges(
    live: LiveState,
    today: Summary,
    tank: com.abeernch.civicobd.core.Tank?,
    settings: AppSettings,
    lastTrip: com.abeernch.civicobd.core.TripSummary?,
) {
    // Parked: the last drive's summary instead of a screen of empty readings.
    if (!live.trip.active && lastTrip != null) {
        Parked(lastTrip, today, tank, settings)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            // Landscape: the big number on the left, the tiles scroll on the right.
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Hero(live, tank, settings, scale = 0.75f)
                }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1.25f).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(6.dp))
                    GaugeTiles(live, today, tank, settings)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Hero(live, tank, settings)
                Spacer(Modifier.height(28.dp))
                GaugeTiles(live, today, tank, settings)
            }
        }
    }
}

@Composable
private fun Hero(live: LiveState, tank: com.abeernch.civicobd.core.Tank?, settings: AppSettings, scale: Float = 1f) {
    val d = live.derived
    val hero = heroReading(live)
    Column {
        Spacer(Modifier.height(6.dp))
        ShiftCue(live.values[0x0C], d)
        Spacer(Modifier.height(10.dp))
        val heroTint by androidx.compose.animation.animateColorAsState(
            if (hero.fuelCut) Palette.Accent else Palette.Value, Motion.fade(400), label = "hero",
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                if (hero.value == "--") {
                    // No reading: a short accent dash, sitting where the number will appear.
                    val style = heroStyle(scale)
                    Box(Modifier.height(with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp() }), contentAlignment = Alignment.CenterStart) {
                        Box(
                            Modifier.width(44.dp).height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Palette.Accent.copy(alpha = 0.6f))
                        )
                    }
                } else {
                    RollingNumber(hero.value, heroStyle(scale).copy(color = heroTint))
                }
                Text(hero.unit, style = Type.label.copy(fontSize = Type.sz(18f)))
            }
            FuelIndicator(tank, settings)
        }
        Spacer(Modifier.height(16.dp))
        EconomyBar(instant = d.instantKmL, average = live.trip.kmPerL)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Parked(
    last: com.abeernch.civicobd.core.TripSummary,
    today: Summary,
    tank: com.abeernch.civicobd.core.Tank?,
    settings: AppSettings,
) {
    val todayBlock: @Composable () -> Unit = {
        Section("Today") {
            Row(Modifier.fillMaxWidth()) {
                Tile("Distance", fmt(today.distanceKm, 1), "km", Modifier.weight(1f))
                Tile("Economy", fmt(today.kmPerL), "km/L", Modifier.weight(1f))
                Tile("Cost", "%,d".format(today.cost.toLong()), "Rs", Modifier.weight(1f))
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState())) {
                    TripSummaryCard(last, title = "Last drive")
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    FuelIndicator(tank, settings)
                    Spacer(Modifier.height(16.dp))
                    todayBlock()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Parked", style = Type.title)
                        Text("Starts recording when the engine runs", style = Type.label)
                    }
                    FuelIndicator(tank, settings)
                }
                TripSummaryCard(last, title = "Last drive")
                Spacer(Modifier.height(24.dp))
                todayBlock()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun heroStyle(scale: Float) = Type.hero.let {
    if (scale == 1f) it else it.copy(fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale)
}

@Composable
private fun GaugeTiles(live: LiveState, today: Summary, tank: com.abeernch.civicobd.core.Tank?, settings: AppSettings) {
    val d = live.derived
    val speed = live.values[0x0D]
    val rpm = live.values[0x0C]
    val trip = live.trip
    val running = live.link == Link.Live && (rpm ?: 0.0) > 0

    Section("Fuel economy  km/L") {
        Row(Modifier.fillMaxWidth()) {
            Tile("10 s", fmt(d.avg10KmL), "", Modifier.weight(1f))
            Tile("Trip", fmt(trip.kmPerL), "", Modifier.weight(1f))
            Tile("Today", fmt(today.kmPerL), "", Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(20.dp))

    Section("This trip") {
        Row(Modifier.fillMaxWidth()) {
            Tile("Distance", fmt(trip.distanceKm, 2), "km", Modifier.weight(1f))
            Tile("Fuel", fmt(trip.fuelL, 2), "L", Modifier.weight(1f))
            Tile("Cost", fmt(trip.fuelL * live.fuelPrice, 0), "PKR", Modifier.weight(1f))
        }
        if (tank?.kmPerL != null) {
            Row(Modifier.fillMaxWidth()) {
                Tile("Since fill", fmt(tank.kmPerL), "km/L", Modifier.weight(1f))
                Tile("Avg speed", fmt(trip.avgSpeed, 0), "km/h", Modifier.weight(1f))
                Tile("Max speed", fmt(trip.maxSpeed, 0), "km/h", Modifier.weight(1f))
            }
        }
    }
    Spacer(Modifier.height(20.dp))

    Section("Engine") {
        Row(Modifier.fillMaxWidth()) {
            val gearText = when {
                !running -> "--"
                d.gear == 0 -> "N"
                else -> d.gear.toString()
            }
            Tile("Gear", gearText, "", Modifier.weight(1f))
            Tile("Speed", fmt(speed, 0), "km/h", Modifier.weight(1f))
            Tile("RPM", fmt(rpm, 0), "", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            val coolant = live.values[0x05]
            Tile(
                "Coolant", fmt(coolant, 0), "°C", Modifier.weight(1f),
                color = if ((coolant ?: 0.0) > 105) Palette.Warning else Palette.Value,
            )
            val volts = live.voltage
            Tile(
                "Battery", fmt(volts), "V", Modifier.weight(1f),
                color = if (running && volts != null && volts < 13.2) Palette.Caution else Palette.Value,
            )
            Tile("Throttle", fmt(d.throttleRel, 0), "%", Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(24.dp))
}

// ---- Live map ----

@Composable
private fun DriveMap(live: LiveState, settings: AppSettings, locationOk: Boolean, onSwipeBack: () -> Unit) {
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

    val map: @Composable (Modifier) -> Unit = { m ->
        Box(m.clipToBounds()) {
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
            val message = when {
                !locationOk -> "Allow location in Settings > Route recording to see the map"
                live.position == null && live.trip.active -> "Waiting for GPS"
                live.position == null -> "The map follows the car once a trip starts"
                else -> null
            }
            message?.let { MapNote(it, Modifier.align(Alignment.TopCenter)) }
            if (!follow && live.position != null) {
                Text(
                    "Re-centre",
                    style = Type.body.copy(color = Palette.Accent, fontSize = Type.sz(15f)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Palette.Background.copy(alpha = 0.85f))
                        .pressable { follow = true; touchedAt = 0L }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Text(
                tiles.credit,
                style = Type.label.copy(fontSize = Type.sz(10f), color = Palette.Label),
                modifier = Modifier.align(Alignment.BottomEnd).background(Palette.Background.copy(alpha = 0.6f)).padding(4.dp),
            )
        }
    }

    // A swipe to the right on the numbers goes back to the gauges.
    val swipeBack = Modifier.pointerInput(Unit) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = { if (total > 80.dp.toPx()) onSwipeBack() },
        ) { _, dx -> total += dx }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                map(Modifier.weight(1f).fillMaxHeight())
                MapReadout(live, vertical = true, modifier = Modifier.width(250.dp).fillMaxHeight().then(swipeBack))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                map(Modifier.fillMaxWidth().weight(1f))
                MapReadout(live, vertical = false, modifier = Modifier.fillMaxWidth().then(swipeBack))
            }
        }
    }
}

@Composable
private fun MapNote(text: String, modifier: Modifier) {
    Text(
        text,
        style = Type.label.copy(color = Palette.Value),
        modifier = modifier
            .padding(top = 12.dp, start = 16.dp, end = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Background.copy(alpha = 0.85f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** The numbers kept under the map: speed, instant economy and trip average, with the shift light. */
@Composable
private fun MapReadout(live: LiveState, vertical: Boolean, modifier: Modifier) {
    val hero = heroReading(live)
    val speed = live.values[0x0D]
    val running = live.link == Link.Live && (live.values[0x0C] ?: 0.0) > 0
    val heroColor by androidx.compose.animation.animateColorAsState(
        if (hero.fuelCut) Palette.Accent else Palette.Value, Motion.fade(400), label = "mapHero",
    )
    val big = Type.value.copy(fontSize = Type.sz(52f), lineHeight = Type.sz(54f))
    @Composable
    fun Reading(value: String, unit: String, style: androidx.compose.ui.text.TextStyle, m: Modifier = Modifier) {
        Column(m) {
            if (value == "--") Text("–", style = style.copy(color = Palette.Label.copy(alpha = 0.45f)))
            else RollingNumber(value, style)
            Text(unit, style = Type.label, maxLines = 1)
        }
    }
    Column(
        modifier
            .background(Palette.Surface)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = if (vertical) Arrangement.Center else Arrangement.Top,
    ) {
        ShiftCue(live.values[0x0C], live.derived, height = 8.dp)
        Spacer(Modifier.height(8.dp))
        val heroStyle = big.copy(color = heroColor)
        val sideStyle = Type.value
        if (vertical) {
            Reading(hero.value, hero.unit, heroStyle)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Reading(if (running) fmt(speed, 0) else "--", "km/h", sideStyle, Modifier.weight(1f))
                Reading(fmt(live.trip.kmPerL), "km/L trip", sideStyle, Modifier.weight(1f))
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Reading(if (running) fmt(speed, 0) else "--", "km/h", sideStyle, Modifier.weight(1f))
                Reading(hero.value, hero.unit, heroStyle, Modifier.weight(1.4f))
                Reading(fmt(live.trip.kmPerL), "km/L  trip", sideStyle, Modifier.weight(1f))
            }
        }
    }
}

/**
 * A thin line from 0 to 30 km/L. The accent fill is the instant value,
 * the white tick is the trip average, so you can see at a glance if you are above or below it.
 */
@Composable
private fun EconomyBar(instant: Double?, average: Double?) {
    val scale = 30.0
    val fill by animateFloatAsState(
        ((instant ?: 0.0) / scale).coerceIn(0.0, 1.0).toFloat(), Motion.settle(), label = "fill",
    )
    val mark by animateFloatAsState(
        ((average ?: 0.0) / scale).coerceIn(0.0, 1.0).toFloat(), Motion.glide(), label = "mark",
    )
    val accent = Palette.Accent
    val tick = Palette.Value
    val track = Palette.Hairline
    Column {
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val y = size.height / 2
            val w = size.width
            drawLine(track, Offset(0f, y), Offset(w, y), 2.dp.toPx(), StrokeCap.Round)
            if (fill > 0f) drawLine(accent, Offset(0f, y), Offset(w * fill, y), 2.dp.toPx(), StrokeCap.Round)
            if (average != null) {
                drawLine(tick, Offset(w * mark, 0f), Offset(w * mark, size.height), 1.5.dp.toPx(), StrokeCap.Round)
            }
        }
        val small = Type.label.copy(fontSize = Type.sz(12f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("0", style = small)
            Text(if (average != null) "trip average ${fmt(average)}" else "", style = small.copy(color = Palette.Value))
            Text("30", style = small)
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
            drawRoundRect(Palette.Hairline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
            val w = if (d.lugging) size.width else size.width * fill
            if (w > 0f) {
                drawRoundRect(color, size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
            }
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
