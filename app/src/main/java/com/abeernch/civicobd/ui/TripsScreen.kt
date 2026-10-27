package com.abeernch.civicobd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.ColorScale
import com.abeernch.civicobd.core.RecordingRow
import com.abeernch.civicobd.core.Recordings
import com.abeernch.civicobd.core.RouteGroup
import com.abeernch.civicobd.core.RouteGroups
import com.abeernch.civicobd.core.RoutePoint
import com.abeernch.civicobd.core.Series
import com.abeernch.civicobd.core.TripEnds
import com.abeernch.civicobd.core.TripRow
import com.abeernch.civicobd.core.TripStore
import com.abeernch.civicobd.core.TripSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt get() = SimpleDateFormat("EEE d MMM", Locale.US)
private val clockFmt get() = SimpleDateFormat("HH:mm", Locale.US)

/**
 * Every drive, and the routes driven more than once. Two views under one switch at the top,
 * so the list stays a plain list and comparisons have their own page.
 * A drive opens to its summary, route map button and charts.
 */
@Composable
fun TripsScreen(
    store: TripStore,
    recordings: Recordings,
    version: Int,
    settings: AppSettings,
    openTripId: Long?,
    onOpened: () -> Unit,
    onExport: (RecordingRow, List<Series>, ExportKind) -> Unit,
    onExportRoute: (RecordingRow, List<RoutePoint>, Boolean, ColorScale, Pair<Double, Double>) -> Unit,
    onDelete: (TripRow, RecordingRow?) -> Unit,
    onMerge: (TripRow, TripRow, (TripRow) -> Unit) -> Unit = { _, _, _ -> },
    activeTripId: Long = -1,
) {
    var view by rememberSaveable { mutableStateOf(0) }
    var trips by remember { mutableStateOf(emptyList<TripRow>()) }
    var recs by remember { mutableStateOf(emptyMap<Long, RecordingRow>()) }
    var open by remember { mutableStateOf<TripRow?>(null) }
    var mapFor by remember { mutableStateOf<RecordingRow?>(null) }
    var heatOpen by remember { mutableStateOf(false) }

    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            trips = store.between(0, Long.MAX_VALUE)
            recs = recordings.all().mapNotNull { r -> r.tripId?.let { it to r } }.toMap()
        }
    }
    // Opened from a trip-summary notification.
    LaunchedEffect(openTripId, trips) {
        if (openTripId != null && trips.isNotEmpty()) {
            trips.firstOrNull { it.id == openTripId }?.let { open = it; view = 0 }
            onOpened()
        }
    }

    // Route map of one drive
    mapFor?.let { rec ->
        RouteMapFor(recordings, rec, settings, onBack = { mapFor = null }, onExportRoute = onExportRoute)
        return
    }
    // Economy of every road stretch, all drives together
    if (heatOpen) {
        AllDrivesMap(recordings, version, settings) { heatOpen = false }
        return
    }
    // One drive: its summary, then the charts and the route map button
    open?.let { trip ->
        BackHandler { open = null }
        val rec = recs[trip.id]
        var summary by remember(trip.id) { mutableStateOf<TripSummary?>(null) }
        LaunchedEffect(trip.id) { summary = withContext(Dispatchers.IO) { TripSummary.of(trip, store) } }
        // The drive just before this one, for merging two drives that were really one.
        val previous = trips.filter { it.startMs < trip.startMs }.maxByOrNull { it.startMs }
        var confirmMerge by remember(trip.id) { mutableStateOf(false) }
        val header: @Composable () -> Unit = {
            Column(Modifier.padding(horizontal = 20.dp)) {
                summary?.let { TripSummaryCard(it) }
                if (previous != null && trip.id == activeTripId) {
                    // The app is still writing this drive (driving, or paused at a stop).
                    Spacer(Modifier.height(10.dp))
                    Text("Merging is available once this drive has ended.", style = Type.label)
                } else if (previous != null && previous.id != activeTripId) {
                    Spacer(Modifier.height(10.dp))
                    OutlineButton(
                        if (confirmMerge) "Tap again to merge into one drive"
                        else "Merge with ${dayFmt.format(Date(previous.startMs))} ${clockFmt.format(Date(previous.startMs))} (${fmt(previous.distanceKm, 1)} km)",
                        color = if (confirmMerge) Palette.Accent else Palette.Label,
                    ) {
                        if (confirmMerge) {
                            confirmMerge = false
                            onMerge(previous, trip) { merged -> open = merged }
                        } else {
                            confirmMerge = true
                        }
                    }
                }
            }
        }
        if (rec != null) {
            RecordingView(
                recordings, rec, onBack = { open = null }, onExport = onExport,
                onDelete = { onDelete(trip, rec); open = null }, onMap = { mapFor = rec }, header = header,
            )
        } else {
            Column(Modifier.fillMaxSize().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BackRow("${dayFmt.format(Date(trip.startMs))}, ${clockFmt.format(Date(trip.startMs))}") { open = null }
                header()
                Text("This drive has no recording, only its totals.", style = Type.label, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            SegmentSwitch(listOf("Drives", "Routes"), view.toFloat()) { view = it }
        }
        if (view == 0) {
            DriveList(trips, recs) { open = it }
        } else {
            RoutesView(trips, recs, recordings, settings, onOpen = { open = it }, onAllDrivesMap = { heatOpen = true })
        }
    }
}

@Composable
private fun BackRow(title: String, onBack: () -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Back", style = Type.body.copy(color = Palette.Accent),
            modifier = Modifier.pressable(onClick = onBack).padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
        )
        Text(title, style = Type.title, maxLines = 1)
    }
}

// ---- Drives ----

@Composable
private fun DriveList(trips: List<TripRow>, recs: Map<Long, RecordingRow>, onOpen: (TripRow) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (trips.isEmpty()) {
            item { Text("No drives yet. They are recorded automatically once the car connects.", style = Type.label) }
        }
        items(trips, key = { it.id }) { t -> DriveCard(t, recs[t.id]?.source) { onOpen(t) } }
    }
}

@Composable
private fun DriveCard(t: TripRow, source: String?, onClick: () -> Unit) {
    val kml = if (t.fuelL > 0.01 && t.distanceKm > 0.05) t.distanceKm / t.fuelL else null
    val minutes = ((t.endMs - t.startMs) / 60_000).coerceAtLeast(0)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(12.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dayFmt.format(Date(t.startMs)), style = Type.body)
                if (source != null && source != "Live") {
                    Spacer(Modifier.width(8.dp))
                    Text(source, style = Type.label.copy(fontSize = Type.sz(12f)))
                }
            }
            Text(
                "${clockFmt.format(Date(t.startMs))}  ·  ${fmt(t.distanceKm, 1)} km  ·  $minutes min  ·  Rs " + "%,d".format(t.cost.toLong()),
                style = Type.label,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmt(kml), style = Type.value.copy(fontSize = Type.sz(26f), lineHeight = Type.sz(28f)))
            Text("km/L", style = Type.label.copy(fontSize = Type.sz(12f)))
        }
    }
}

/** The figures of one finished drive. Used on Drive when parked and at the top of a drive's page. */
@Composable
fun TripSummaryCard(s: TripSummary, title: String? = null) {
    val t = s.trip
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(
            title ?: "${dayFmt.format(Date(t.startMs))}  ${clockFmt.format(Date(t.startMs))} to ${clockFmt.format(Date(t.endMs))}",
            style = Type.section,
        )
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
            Text(fmt(s.kmPerL), style = Type.value.copy(fontSize = Type.sz(52f), lineHeight = Type.sz(54f)))
            Text("  km/L", style = Type.label.copy(fontSize = Type.sz(16f)), modifier = Modifier.padding(bottom = 8.dp))
        }
        s.versusAverage?.let { v ->
            val better = (s.kmPerL ?: 0.0) >= (s.averageKmL ?: 0.0)
            Text(
                "$v of ${fmt(s.averageKmL)}",
                style = Type.label.copy(color = if (better) Palette.Accent else Palette.Caution),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Tile("Distance", fmt(t.distanceKm, 1), "km", Modifier.weight(1f))
            Tile("Time", "${s.minutes.toInt()}", "min", Modifier.weight(1f))
            Tile("Cost", "%,d".format(t.cost.toLong()), "Rs", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Tile("Average", fmt(s.avgSpeed, 0), "km/h", Modifier.weight(1f))
            Tile("Top", fmt(t.maxSpeed, 0), "km/h", Modifier.weight(1f))
            Tile("Standing", "${(t.idleS / 60).toInt()}", "min", Modifier.weight(1f))
        }
    }
}

// ---- Routes ----

@Composable
private fun RoutesView(
    trips: List<TripRow>,
    recs: Map<Long, RecordingRow>,
    recordings: Recordings,
    settings: AppSettings,
    onOpen: (TripRow) -> Unit,
    onAllDrivesMap: () -> Unit,
) {
    var groups by remember { mutableStateOf<List<RouteGroup>?>(null) }
    var namesVersion by remember { mutableStateOf(0) }
    LaunchedEffect(trips, recs, namesVersion) {
        groups = withContext(Dispatchers.IO) {
            val ends = trips.mapNotNull { t ->
                val rec = recs[t.id] ?: return@mapNotNull null
                val e = recordings.endpoints(rec.id) ?: return@mapNotNull null
                TripEnds(t, e[0], e[1], e[2], e[3])
            }
            RouteGroups.build(ends, routeNames(settings))
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { OutlineButton("Fuel map of all drives", color = Palette.Accent, onClick = onAllDrivesMap) }
        val list = groups
        when {
            list == null -> item { Text("Finding repeated routes...", style = Type.label) }
            list.isEmpty() -> item {
                Text(
                    "A route appears here once you have driven between the same two places twice, " +
                        "with GPS on. Drives are compared by time, economy and cost.",
                    style = Type.label,
                )
            }
            else -> items(list, key = { it.key }) { g ->
                RouteCard(g, onOpen, onRename = { name ->
                    saveRouteName(settings, g.key, name); namesVersion++
                })
            }
        }
    }
}

private fun routeNames(settings: AppSettings): Map<Long, String> = try {
    val o = JSONObject(settings.routeNames)
    o.keys().asSequence().associate { it.toLong() to o.getString(it) }
} catch (_: Exception) {
    emptyMap()
}

private fun saveRouteName(settings: AppSettings, key: Long, name: String) {
    val o = try { JSONObject(settings.routeNames) } catch (_: Exception) { JSONObject() }
    if (name.isBlank()) o.remove(key.toString()) else o.put(key.toString(), name.trim())
    settings.routeNames = o.toString()
}

/** One repeated route: averages up front, best and worst, and the drives behind it on request. */
@Composable
private fun RouteCard(g: RouteGroup, onOpen: (TripRow) -> Unit, onRename: (String) -> Unit) {
    var expanded by rememberSaveable(g.key) { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var name by remember(g.name) { mutableStateOf(g.name) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                BasicTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    textStyle = Type.title, cursorBrush = SolidColor(Palette.Accent),
                    modifier = Modifier.weight(1f).border(1.dp, Palette.Hairline, RoundedCornerShape(8.dp)).padding(8.dp),
                )
                Text(
                    "Save", style = Type.body.copy(color = Palette.Accent),
                    modifier = Modifier.pressable { editing = false; onRename(name) }.padding(start = 12.dp),
                )
            } else {
                Text(g.name, style = Type.title, modifier = Modifier.weight(1f), maxLines = 1)
                Text(
                    "Rename", style = Type.label.copy(color = Palette.Accent),
                    modifier = Modifier.pressable { editing = true }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }
        Text("${g.trips.size} drives  ·  about ${fmt(g.distanceKm, 1)} km", style = Type.label)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Tile("Economy", fmt(g.avgKmL), "km/L", Modifier.weight(1f))
            Tile("Time", "${g.avgMinutes.toInt()}", "min", Modifier.weight(1f))
            Tile("Cost", "%,d".format(g.avgCost.toLong()), "Rs", Modifier.weight(1f))
        }
        Text("Averages", style = Type.label.copy(fontSize = Type.sz(12f)))
        Spacer(Modifier.height(8.dp))
        g.best?.let { ReadingRow("Best economy", "${fmt(it.distanceKm / it.fuelL)} km/L  ${dayFmt.format(Date(it.startMs))}", color = Palette.Accent) }
        g.worst?.let { ReadingRow("Worst economy", "${fmt(it.distanceKm / it.fuelL)} km/L  ${dayFmt.format(Date(it.startMs))}") }
        g.fastest?.let { ReadingRow("Quickest", "${(it.endMs - it.startMs) / 60_000} min  ${dayFmt.format(Date(it.startMs))}") }
        Text(
            if (expanded) "Hide drives" else "Show the ${g.trips.size} drives",
            style = Type.body.copy(color = Palette.Accent),
            modifier = Modifier.pressable { expanded = !expanded }.padding(vertical = 10.dp),
        )
        if (expanded) {
            val top = g.trips.maxOf { if (it.fuelL > 0.01) it.distanceKm / it.fuelL else 0.0 }.coerceAtLeast(1.0)
            g.trips.forEach { t -> RouteDriveRow(t, top) { onOpen(t) } }
        }
    }
}

/** A drive within a route: date, time taken, and its economy as a bar against the route's best. */
@Composable
private fun RouteDriveRow(t: TripRow, best: Double, onClick: () -> Unit) {
    val kml = if (t.fuelL > 0.01 && t.distanceKm > 0.05) t.distanceKm / t.fuelL else null
    Column(Modifier.fillMaxWidth().pressable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${dayFmt.format(Date(t.startMs))}  ${clockFmt.format(Date(t.startMs))}", style = Type.label, modifier = Modifier.weight(1f))
            Text("${(t.endMs - t.startMs) / 60_000} min", style = Type.label)
            Spacer(Modifier.width(14.dp))
            Text(fmt(kml), style = Type.body)
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Hairline)) {
            Box(
                Modifier.fillMaxWidth(((kml ?: 0.0) / best).toFloat().coerceIn(0f, 1f)).height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Palette.Accent)
            )
        }
    }
}

// ---- Maps opened from here ----

@Composable
private fun RouteMapFor(
    recordings: Recordings,
    rec: RecordingRow,
    settings: AppSettings,
    onBack: () -> Unit,
    onExportRoute: (RecordingRow, List<RoutePoint>, Boolean, ColorScale, Pair<Double, Double>) -> Unit,
) {
    var route by remember(rec.id) { mutableStateOf<List<RoutePoint>?>(null) }
    LaunchedEffect(rec.id) { route = withContext(Dispatchers.IO) { com.abeernch.civicobd.core.Route.load(recordings, rec.id) } }
    val loaded = route
    if (loaded == null || loaded.size < 2) {
        BackHandler(onBack = onBack)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (loaded == null) "Loading route..." else "This drive has no usable GPS track.", style = Type.label)
        }
    } else {
        RouteMapScreen(
            loaded,
            SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(rec.startMs)),
            settings,
            onBack = onBack,
            onExport = { gpx, scale, range -> onExportRoute(rec, loaded, gpx, scale, range) },
        )
    }
}

@Composable
private fun AllDrivesMap(recordings: Recordings, version: Int, settings: AppSettings, onBack: () -> Unit) {
    var heat by remember { mutableStateOf<List<com.abeernch.civicobd.core.HeatCell>?>(null) }
    LaunchedEffect(version) {
        heat = withContext(Dispatchers.IO) {
            com.abeernch.civicobd.core.Heatmap.build(recordings.all().map { com.abeernch.civicobd.core.Route.load(recordings, it.id) })
        }
    }
    val cells = heat
    if (cells == null) {
        BackHandler(onBack = onBack)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Building the fuel map...", style = Type.label) }
    } else {
        HeatMapScreen(cells, settings, onBack)
    }
}
