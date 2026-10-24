package com.abeernch.civicobd.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abeernch.civicobd.core.Period
import com.abeernch.civicobd.core.Summary
import com.abeernch.civicobd.core.TripRow
import com.abeernch.civicobd.core.TripStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Metric(val label: String, val unit: String) {
    Distance("Distance", "km"),
    Fuel("Fuel", "L"),
    Cost("Cost", "PKR"),
    Economy("Economy", "km/L"),
    Speed("Avg speed", "km/h"),
}

private data class Bucket(val label: String, val summary: Summary)

@Composable
fun StatsScreen(store: TripStore, version: Int, onExportTrips: (List<TripRow>) -> Unit) {
    var period by remember { mutableStateOf(Period.Week) }
    var metric by remember { mutableStateOf(Metric.Distance) }
    var trips by remember { mutableStateOf(emptyList<TripRow>()) }

    // Reload whenever the period changes or the service writes a trip.
    LaunchedEffect(period, version) {
        val (from, to) = period.range()
        trips = withContext(Dispatchers.IO) { store.between(from, to) }
    }
    val total = Summary.of(trips)
    val buckets = buckets(trips, period.byMonth)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Chips(Period.entries.toList(), period, { it.label }) { period = it }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Section(period.label) {
                    Row(Modifier.fillMaxWidth()) {
                        Tile("Distance", fmt(total.distanceKm, 1), "km", Modifier.weight(1f))
                        Tile("Fuel", fmt(total.fuelL, 2), "L", Modifier.weight(1f))
                        Tile("Economy", fmt(total.kmPerL), "km/L", Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Tile("Cost", fmt(total.cost, 0), "PKR", Modifier.weight(1f))
                        Tile("Avg speed", fmt(total.avgSpeed), "km/h", Modifier.weight(1f))
                        Tile("Trips", total.trips.toString(), "", Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Tile("Total time", if (total.totalS > 0) duration(total.totalS) else "--", "", Modifier.weight(1f))
                        Tile("Driving", if (total.totalS > 0) duration(total.driveS) else "--", "", Modifier.weight(1f))
                        Tile("Standing", if (total.totalS > 0) duration(total.idleS) else "--", "", Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Column {
                Chips(Metric.entries.toList(), metric, { it.label }) { metric = it }
                Spacer(Modifier.height(16.dp))
                BarChart(buckets, metric)
            }
        }
        item {
            Text(
                if (trips.isEmpty()) "No trips in this period yet" else "Trips",
                style = Type.label, modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        items(trips, key = { it.id }) { t -> TripCard(t) }
        if (trips.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    OutlineButton("Export these trips as CSV") { onExportTrips(trips) }
                }
            }
        }
    }
}

/** Groups trips by day (or month for long periods), oldest first, at most the last 31 groups. */
private fun buckets(trips: List<TripRow>, byMonth: Boolean): List<Bucket> {
    val keyFormat = SimpleDateFormat(if (byMonth) "yyyy-MM" else "yyyy-MM-dd", Locale.US)
    val labelFormat = SimpleDateFormat(if (byMonth) "MMM" else "dd.MM", Locale.US)
    return trips
        .groupBy { keyFormat.format(Date(it.startMs)) }
        .toSortedMap()
        .map { (_, rows) -> Bucket(labelFormat.format(Date(rows.first().startMs)), Summary.of(rows)) }
        .takeLast(31)
}

private fun valueOf(s: Summary, m: Metric): Double? = when (m) {
    Metric.Distance -> s.distanceKm
    Metric.Fuel -> s.fuelL
    Metric.Cost -> s.cost
    Metric.Economy -> s.kmPerL
    Metric.Speed -> s.avgSpeed
}

@Composable
private fun BarChart(buckets: List<Bucket>, metric: Metric) {
    val chartHeight = 140.dp
    val max = buckets.maxOfOrNull { valueOf(it.summary, metric) ?: 0.0 }?.takeIf { it > 0 } ?: 1.0
    val showValues = buckets.size <= 10
    val decimals = if (metric == Metric.Cost) 0 else 1

    Column(Modifier.padding(horizontal = 20.dp)) {
        if (buckets.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(chartHeight), contentAlignment = Alignment.Center) {
                Text("Nothing to chart yet", style = Type.label)
            }
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().height(chartHeight + 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            buckets.forEachIndexed { index, b ->
                val value = valueOf(b.summary, metric)
                val target = chartHeight * ((value ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
                val barHeight by animateDpAsState(target, Motion.glide(), label = "bar")
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (showValues) {
                        Text(fmt(value, decimals), style = Type.label.copy(fontSize = Type.sz(13f)), maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                    }
                    // Slim bars, never wider than 28 dp, so one or two days do not turn into slabs.
                    Box(
                        Modifier
                            .widthIn(max = 28.dp)
                            .fillMaxWidth()
                            .height(barHeight)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (index == buckets.lastIndex) Palette.Accent else Palette.Value.copy(alpha = 0.85f))
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            buckets.forEach { b ->
                Text(
                    if (showValues || buckets.size <= 16) b.label else "",
                    style = Type.label.copy(fontSize = Type.sz(13f)),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("${metric.label}  ${metric.unit}", style = Type.label)
    }
}

@Composable
private fun TripCard(t: TripRow) {
    val day = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(t.startMs))
    val clock = SimpleDateFormat("HH:mm", Locale.US)
    val s = Summary.of(listOf(t))
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .border(1.dp, Palette.Hairline, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(day, style = Type.body, modifier = Modifier.weight(1f))
            Text("${clock.format(Date(t.startMs))}  to  ${clock.format(Date(t.endMs))}", style = Type.label)
        }
        Row(Modifier.fillMaxWidth()) {
            Tile("Distance", fmt(t.distanceKm, 2), "km", Modifier.weight(1f))
            Tile("Economy", fmt(s.kmPerL), "km/L", Modifier.weight(1f))
            Tile("Cost", fmt(t.cost, 0), "PKR", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Tile("Fuel", fmt(t.fuelL, 2), "L", Modifier.weight(1f))
            Tile("Avg speed", fmt(s.avgSpeed), "km/h", Modifier.weight(1f))
            Tile("Standing", duration(t.idleS), "", Modifier.weight(1f))
        }
    }
}

/** A single-choice row of text chips. The chosen one gets a cyan outline. */
@Composable
fun <T> Chips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    inset: androidx.compose.ui.unit.Dp = 20.dp,
    onPick: (T) -> Unit,
) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = inset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            val edge by animateColorAsState(if (on) Palette.Accent else Palette.Hairline, Motion.fade(250), label = "chip")
            val fill by animateColorAsState(if (on) Palette.AccentSoft else Palette.Background, Motion.fade(250), label = "chipFill")
            Text(
                label(option),
                style = Type.body.copy(color = if (on) Palette.Value else Palette.Label, fontSize = Type.sz(15f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(fill)
                    .border(1.dp, edge, RoundedCornerShape(16.dp))
                    .pressable { onPick(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
    }
}
