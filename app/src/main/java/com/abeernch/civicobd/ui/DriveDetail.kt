package com.abeernch.civicobd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.Channel
import com.abeernch.civicobd.core.RecordingRow
import com.abeernch.civicobd.core.Recordings
import com.abeernch.civicobd.core.Route
import com.abeernch.civicobd.core.RouteContext
import com.abeernch.civicobd.core.RouteContexts
import com.abeernch.civicobd.core.RoutePoint
import com.abeernch.civicobd.core.Series
import com.abeernch.civicobd.core.TripRow
import com.abeernch.civicobd.core.TripStore
import com.abeernch.civicobd.core.TripSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** A reading that can be plotted on the drive's chart. */
private enum class Plot(val label: String, val channel: String, val unit: String, val decimals: Int) {
    Speed("Speed", "Vehicle speed", "km/h", 0),
    Economy("km/L", "Calculated instant fuel consumption", "km/L", 1),
    Rpm("RPM", "Engine RPM", "rpm", 0),
    Throttle("Throttle", "Throttle position", "%", 0),
    Coolant("Coolant", "Engine coolant temperature", "°C", 0),
    Load("Load", "Calculated engine load value", "%", 0);

    val color: Color
        get() = when (this) {
            Speed -> Lux.Txt
            Economy -> Lux.Green
            Rpm -> Lux.Gold
            Throttle -> Color(0xFF8FB4FF)
            Coolant -> Color(0xFFE39A8C)
            Load -> Color(0xFFC7A6E8)
        }
}

/** Averages from the recording that say how the engine was used. */
private data class EngineFigures(val avgRpm: Double?, val coolantMax: Double?, val idleFuelL: Double?)

/**
 * One drive, with everything up front: the economy and route, six key figures with how this drive compares,
 * then the route map or a chart of any three readings on one plot, then the engine's figures.
 */
@Composable
internal fun DriveDetail(
    trip: TripRow,
    rec: RecordingRow?,
    store: TripStore,
    recordings: Recordings,
    settings: AppSettings,
    onBack: () -> Unit,
    onExport: (RecordingRow, ExportKind) -> Unit,
    onDelete: () -> Unit,
    onFullMap: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var summary by remember(trip.id) { mutableStateOf<TripSummary?>(null) }
    var route by remember(trip.id) { mutableStateOf<RouteContext?>(null) }
    var points by remember(trip.id) { mutableStateOf<List<RoutePoint>?>(null) }
    var channels by remember(trip.id) { mutableStateOf(emptyList<Channel>()) }
    var engine by remember(trip.id) { mutableStateOf<EngineFigures?>(null) }
    LaunchedEffect(trip.id) {
        summary = withContext(Dispatchers.IO) { TripSummary.of(trip, store) }
        if (rec != null) {
            channels = withContext(Dispatchers.IO) { recordings.channels(rec.id) }
            points = withContext(Dispatchers.IO) { Route.load(recordings, rec.id) }
            engine = withContext(Dispatchers.IO) { engineFigures(recordings, rec.id, channels) }
        }
        route = withContext(Dispatchers.IO) { runCatching { RouteContexts.of(trip, store, recordings, routeNames(settings)) }.getOrNull() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TopBar(rec, onBack, onExport, onDelete, onFullMap = if ((points?.size ?: 0) >= 2) onFullMap else null)
        Headline(trip, summary, route)
        DetailsCard(trip, summary, route)
        if (rec != null) MapChartCard(rec, recordings, settings, points, channels, onFullMap)
        engine?.takeIf { it.avgRpm != null || it.coolantMax != null || it.idleFuelL != null }?.let { EngineCard(it) }
        if (rec == null) {
            Text("This drive has no recording, only its totals.", style = Type.label, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun TopBar(
    rec: RecordingRow?,
    onBack: () -> Unit,
    onExport: (RecordingRow, ExportKind) -> Unit,
    onDelete: () -> Unit,
    onFullMap: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).semantics { contentDescription = "Back" }.pressable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { LineGlyph(LuxIcon.Back, Palette.Value, 24.dp, 1.8f) }
        Spacer(Modifier.weight(1f))
        Box {
            Box(
                Modifier.size(44.dp).semantics { contentDescription = "More: map, export, delete" }.pressable { menu = true; confirm = false },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                    val r = 1.8.dp.toPx()
                    for (y in listOf(5f, 12f, 19f)) drawCircle(Palette.Value, r, androidx.compose.ui.geometry.Offset(size.width / 2, y / 24f * size.height))
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Palette.Surface)) {
                val item: @Composable (String, Color, () -> Unit) -> Unit = { text, color, action ->
                    DropdownMenuItem(text = { Text(text, style = Type.body.copy(color = color)) }, onClick = action)
                }
                onFullMap?.let { open -> item("Full-screen route map", Palette.Value) { menu = false; open() } }
                if (rec != null) {
                    ExportKind.entries.forEach { kind -> item("Export: ${kind.label}", Palette.Value) { menu = false; onExport(rec, kind) } }
                }
                item(if (confirm) "Tap again to delete this drive" else "Delete this drive", if (confirm) Palette.Warning else Palette.Label) {
                    if (confirm) { menu = false; onDelete() } else confirm = true
                }
            }
        }
    }
}

/** The date, the drive's economy as the headline, and where it went. */
@Composable
private fun Headline(trip: TripRow, summary: TripSummary?, route: RouteContext?) {
    val day = SimpleDateFormat("EEE d MMM", Locale.US).format(Date(trip.startMs))
    val clock = SimpleDateFormat("HH:mm", Locale.US)
    val k = summary?.kmPerL ?: if (trip.fuelL > 0.01 && trip.distanceKm > 0.05) trip.distanceKm / trip.fuelL else null
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$day · ${clock.format(Date(trip.startMs))} to ${clock.format(Date(trip.endMs))}", style = Type.label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(k?.let { fmt(it) } ?: "–", style = lux(64.sp, FontWeight.Light, Lux.eco(k)).copy(lineHeight = 64.sp))
            Text("  km/L", style = Type.label.copy(fontSize = Type.sz(18f)), modifier = Modifier.padding(bottom = 10.dp))
        }
        val line = buildAnnotatedString {
            if (route != null) {
                append(route.name)
                withStyle(SpanStyle(color = Palette.Label)) { append(" · your ${RouteContexts.ordinal(route.nth)} drive on this route") }
            } else {
                withStyle(SpanStyle(color = Palette.Label)) { append("${fmt(trip.distanceKm, 1)} km in ${((trip.endMs - trip.startMs) / 60_000).coerceAtLeast(0)} min") }
            }
        }
        Text(line, style = Type.body.copy(fontSize = Type.sz(16f)))
    }
}

/** Six figures in two columns, white names over the values, then how the drive compares. */
@Composable
private fun DetailsCard(trip: TripRow, summary: TripSummary?, route: RouteContext?) {
    Card {
        Text("Drive details", style = Type.section.copy(color = Palette.Value, fontSize = Type.sz(19f)), modifier = Modifier.padding(horizontal = 6.dp))
        val avgSpeed = summary?.avgSpeed
        val cells = listOf(
            Triple("Distance", fmt(trip.distanceKm, 1), "km"),
            Triple("Drive time", clockTime(trip.driveS + trip.idleS), ""),
            Triple("Fuel used", fmt(trip.fuelL, 2), "L"),
            Triple("Cost", "%,d".format(trip.cost.roundToInt()), "Rs"),
            Triple("Avg speed", fmt(avgSpeed, 1), "km/h"),
            Triple("Top speed", fmt(trip.maxSpeed, 0), "km/h"),
        )
        cells.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (name, value, unit) -> DetailTile(name, value, unit, Modifier.weight(1f), unitFirst = name == "Cost") }
            }
        }
        val k = summary?.kmPerL
        RouteContexts.comparison(k, route?.usualKmL, summary?.averageKmL)?.let { (text, good) ->
            Text(
                text,
                style = Type.body.copy(color = if (good == true) Lux.Green else if (good == false) Lux.Amber else Palette.Label, fontSize = Type.sz(15f)),
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

@Composable
private fun DetailTile(name: String, value: String, unit: String, modifier: Modifier, unitFirst: Boolean) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(name, style = Type.label.copy(color = Palette.Value), maxLines = 1)
        val unitStyle = SpanStyle(fontSize = Type.sz(15f), color = Palette.Label, fontWeight = FontWeight.Normal)
        Text(
            buildAnnotatedString {
                if (unitFirst && unit.isNotEmpty()) withStyle(unitStyle) { append("$unit ") }
                append(value)
                if (!unitFirst && unit.isNotEmpty()) withStyle(unitStyle) { append(" $unit") }
            },
            style = Type.value.copy(fontSize = Type.sz(30f), lineHeight = Type.sz(34f)),
            maxLines = 1,
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.Surface)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

// ---------------------------------------------------------------- map or chart

@Composable
private fun MapChartCard(
    rec: RecordingRow,
    recordings: Recordings,
    settings: AppSettings,
    points: List<RoutePoint>?,
    channels: List<Channel>,
    onFullMap: () -> Unit,
) {
    val hasRoute = (points?.size ?: 0) >= 2
    var tab by remember(rec.id) { mutableStateOf(-1) }
    // The map first when there is a route, otherwise straight to the chart.
    val shown = if (tab >= 0) tab else if (points == null || hasRoute) 0 else 1
    Card {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Map", "Chart").forEachIndexed { i, label ->
                val on = shown == i
                val color by animateColorAsState(if (on) Palette.Value else Palette.Label, Motion.fade(200), label = "tab$i")
                Column(
                    Modifier
                        .semantics { contentDescription = label }
                        .pressable { tab = i }
                        .padding(end = 22.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text(label, style = Type.section.copy(color = color, fontSize = Type.sz(19f)))
                    Box(Modifier.padding(top = 4.dp).width(if (label == "Map") 38.dp else 50.dp).height(2.dp).background(if (on) Palette.Value else Color.Transparent))
                }
            }
            Spacer(Modifier.weight(1f))
            if (shown == 0 && hasRoute) {
                Box(
                    Modifier.size(44.dp).semantics { contentDescription = "Open the full-screen map" }.pressable(onClick = onFullMap),
                    contentAlignment = Alignment.CenterEnd,
                ) { LineGlyph(LuxIcon.Expand, Palette.Value, 20.dp, 1.8f) }
            }
        }
        if (shown == 0) MiniMap(points, settings, onFullMap) else DriveChart(rec, recordings, channels)
    }
}

@Composable
private fun MiniMap(points: List<RoutePoint>?, settings: AppSettings, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.fillMaxWidth().height(320.dp).clip(shape).background(Palette.Raised)) {
        when {
            points == null -> Text("Loading the route...", style = Type.label, modifier = Modifier.align(Alignment.Center))
            points.size < 2 -> Text("This drive has no usable GPS track.", style = Type.label, modifier = Modifier.align(Alignment.Center))
            else -> {
                val tiles = MapTiles.entries.firstOrNull { it.name == settings.mapTiles } ?: MapTiles.Esri
                val colors = remember(points, Palette.isDark) {
                    IntArray(points.size) { i ->
                        val p = points[i]
                        if (p.fuelCut) Route.FUEL_CUT_COLOR else economyColor(p.kmPerL ?: 0.0).toArgbInt()
                    }
                }
                RouteMap(points, colors, emptyList(), emptyList(), null, tiles) {}
                // The small map is a picture of the route; a tap opens the full map to explore it.
                Box(Modifier.fillMaxSize().semantics { contentDescription = "Route map. Tap to open it full screen" }.pressable(onClick = onOpen))
                Text(
                    tiles.credit,
                    style = Type.label.copy(fontSize = Type.sz(10f)),
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }
        }
    }
    if ((points?.size ?: 0) >= 2) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("km/L", style = Type.label.copy(fontSize = Type.sz(13f)))
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(6.0, 12.0, 15.5, 20.0).forEach { v ->
                    Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(economyColor(v)))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text("poor to good", style = Type.label.copy(fontSize = Type.sz(13f)))
        }
    }
}

/** Up to three readings on one plot, each on its own scale, with a marker to read values at any moment. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriveChart(rec: RecordingRow, recordings: Recordings, channels: List<Channel>) {
    val available = Plot.entries.filter { p -> channels.any { it.name.equals(p.channel, ignoreCase = true) } }
    val picked = remember(rec.id) { mutableStateListOf<Plot>() }
    LaunchedEffect(available) {
        if (picked.isEmpty()) available.filter { it == Plot.Speed || it == Plot.Economy }.forEach { picked += it }
        if (picked.isEmpty()) available.take(1).forEach { picked += it }
    }
    val loaded = remember(rec.id) { mutableStateOf(mapOf<Plot, Series>()) }
    LaunchedEffect(picked.toList()) {
        val missing = picked.filter { it !in loaded.value }
        if (missing.isNotEmpty()) {
            val fresh = withContext(Dispatchers.IO) {
                missing.mapNotNull { p ->
                    channels.firstOrNull { it.name.equals(p.channel, ignoreCase = true) }?.let { ch -> p to recordings.series(rec.id, ch) }
                }.toMap()
            }
            loaded.value = loaded.value + fresh
        }
    }
    val view = remember(rec.id) { ChartView(rec.startMs, rec.endMs) }
    val plots = picked.filter { it in loaded.value }
    val series = plots.map { loaded.value.getValue(it) }

    if (available.isEmpty()) {
        Text("This drive has no readings to chart.", style = Type.label, modifier = Modifier.padding(horizontal = 6.dp))
        return
    }
    Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Plot up to three · tap to add or remove", style = Type.label.copy(fontSize = Type.sz(14f)))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            available.forEach { p ->
                val on = p in picked
                val edge by animateColorAsState(if (on) Palette.Accent else Palette.Hairline, Motion.fade(200), label = "chipEdge")
                Row(
                    Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (on) Palette.AccentSoft else Palette.Raised)
                        .border(1.5.dp, edge, RoundedCornerShape(20.dp))
                        .semantics { contentDescription = "${p.label}${if (on) ", plotted" else ""}" }
                        .pressable {
                            if (on) {
                                if (picked.size > 1) picked.remove(p)
                            } else {
                                if (picked.size >= 3) picked.removeAt(0)
                                picked.add(p)
                            }
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (on) p.color else Palette.Label.copy(alpha = 0.5f)))
                    Spacer(Modifier.width(8.dp))
                    Text(p.label, style = Type.body.copy(fontSize = Type.sz(15f), color = if (on) Palette.Value else Palette.Label))
                }
            }
        }
    }
    // The values at the marker.
    val c = view.cursor
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Raised)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (c == null || series.isEmpty()) {
            Text("Drag across the chart to read values. Pinch to zoom.", style = Type.label.copy(fontSize = Type.sz(14f)))
        } else {
            val text = buildAnnotatedString {
                withStyle(SpanStyle(color = Palette.Label)) { append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(c))) }
                plots.forEachIndexed { i, p ->
                    val s = series[i]
                    val j = s.indexAt(c)
                    append("   ")
                    withStyle(SpanStyle(color = p.color, fontWeight = FontWeight.SemiBold)) {
                        append(if (j >= 0) "%.${p.decimals}f".format(s.v[j]) else "--")
                    }
                    withStyle(SpanStyle(color = Palette.Label)) { append(" ${p.unit}") }
                }
            }
            Text(text, style = Type.body.copy(fontSize = Type.sz(15f)))
        }
    }
    if (series.isNotEmpty()) {
        LineChart(series, plots.map { it.color }, view, normalised = true, height = 250.dp)
        // Each line's own range, so the shared plot can still be read.
        FlowRow(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            plots.forEachIndexed { i, p ->
                val s = series[i]
                if (s.size == 0) return@forEachIndexed
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                for (v in s.v) { lo = minOf(lo, v); hi = maxOf(hi, v) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(14.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(p.color))
                    Spacer(Modifier.width(6.dp))
                    Text("${p.label} ${compact(lo)} to ${compact(hi)} ${p.unit}", style = Type.label.copy(fontSize = Type.sz(13f)))
                }
            }
        }
        if (view.zoomed) {
            Text(
                "Zoom out", style = Type.label.copy(color = Palette.Accent),
                modifier = Modifier.padding(horizontal = 6.dp).pressable { view.reset() }.padding(vertical = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- engine

@Composable
private fun EngineCard(e: EngineFigures) {
    Card {
        Text("Engine", style = Type.section.copy(color = Palette.Value, fontSize = Type.sz(19f)), modifier = Modifier.padding(horizontal = 6.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            listOf(
                Triple(e.avgRpm?.let { "%,d".format(it.roundToInt()) }, "Avg rpm", ""),
                Triple(e.coolantMax?.let { "${it.roundToInt()}°" }, "Coolant max", ""),
                Triple(e.idleFuelL?.let { fmt(it, 2) }, "Fuel standing", "L"),
            ).filter { it.first != null }.forEach { (value, name, unit) ->
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text(if (unit.isEmpty()) value!! else "$value $unit", style = Type.value.copy(fontSize = Type.sz(26f), lineHeight = Type.sz(30f)), maxLines = 1)
                    Text(name, style = Type.label.copy(fontSize = Type.sz(14f)), maxLines = 1)
                }
            }
        }
    }
}

private fun engineFigures(recordings: Recordings, recId: Long, channels: List<Channel>): EngineFigures {
    fun s(name: String) = channels.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { recordings.series(recId, it) }
    val rpm = s("Engine RPM")
    val coolant = s("Engine coolant temperature")
    val rate = s("Calculated instant fuel rate")
    val speed = s("Vehicle speed")
    val running = rpm?.v?.filter { it > 300f }
    val avgRpm = running?.takeIf { it.isNotEmpty() }?.average()
    val coolantMax = coolant?.v?.takeIf { it.isNotEmpty() }?.max()?.toDouble()
    var idle: Double? = null
    if (rate != null && speed != null && rate.size > 1) {
        var litres = 0.0
        for (i in 0 until rate.size - 1) {
            val dtMs = (rate.t[i + 1] - rate.t[i]).coerceIn(0L, 5_000L)
            val j = speed.indexAt(rate.t[i])
            if (j >= 0 && speed.v[j] < 1f) litres += rate.v[i] * dtMs / 3_600_000.0
        }
        idle = litres
    }
    return EngineFigures(avgRpm, coolantMax, idle)
}
