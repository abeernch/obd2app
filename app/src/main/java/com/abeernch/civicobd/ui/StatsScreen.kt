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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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


@Composable
fun StatsScreen(
    store: TripStore,
    version: Int,
    onExportTrips: (List<TripRow>) -> Unit,
    header: @Composable () -> Unit = {},
) {
    var period by remember { mutableStateOf(Period.Week) }
    var offset by remember { mutableStateOf(0) }       // 0 = the current week, month..., -1 the one before
    var metric by remember { mutableStateOf(Metric.Distance) }
    var trips by remember { mutableStateOf(emptyList<TripRow>()) }
    var firstTrip by remember { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }

    val window = remember(period, offset, firstTrip, version) { com.abeernch.civicobd.core.Windows.of(period, offset, firstTripMs = firstTrip) }
    // Reload whenever the window changes or the service writes a trip.
    LaunchedEffect(window.startMs, window.endMs, version) {
        withContext(Dispatchers.IO) {
            trips = store.between(window.startMs, window.endMs)
            firstTrip = store.between(0, Long.MAX_VALUE).lastOrNull()?.startMs
        }
    }
    LaunchedEffect(period, offset) { selected = null }
    val total = Summary.of(trips)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item { header() }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                // The period picker is the section's title: "This week" with a menu of the others.
                Dropdown(Period.entries.toList(), period, { it.label }, Type.title.copy(fontSize = Type.sz(24f))) {
                    period = it; offset = 0
                }
                // The exact dates, with arrows to step to the previous or next week, month or year.
                StepRow(window.title, canBack = window.canStep, canForward = window.canStep && offset < 0,
                    onBack = { offset-- }, onForward = { offset++ })
                Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    Tile("Distance", fmt(total.distanceKm, 1), "km", Modifier.weight(1f))
                    Tile("Fuel", fmt(total.fuelL, 2), "L", Modifier.weight(1f))
                    Tile("Economy", fmt(total.kmPerL), "km/L", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Tile("Cost", fmt(total.cost, 0), "PKR", Modifier.weight(1f))
                    Tile("Avg speed", fmt(total.avgSpeed), "km/h", Modifier.weight(1f))
                    Tile("Drives", total.trips.toString(), "", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth()) {
                    Tile("Total time", if (total.totalS > 0) duration(total.totalS) else "--", "", Modifier.weight(1f))
                    Tile("Driving", if (total.totalS > 0) duration(total.driveS) else "--", "", Modifier.weight(1f))
                    Tile("Standing", if (total.totalS > 0) duration(total.idleS) else "--", "", Modifier.weight(1f))
                }
            }
        }
        item {
            Column {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dropdown(Metric.entries.toList(), metric, { it.label }, Type.section.copy(color = Palette.Value)) { metric = it }
                    Text("  ${metric.unit}", style = Type.label)
                }
                Spacer(Modifier.height(10.dp))
                SlotChart(window.slots, trips, metric, selected) { i -> selected = if (selected == i) null else i }
            }
        }
        // Each drive is listed in the Trips tab; here only the totals, chart and export.
        if (trips.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    OutlineButton("Export these ${trips.size} drives as CSV") { onExportTrips(trips) }
                }
            }
        } else {
            item { Text("No drives in this period", style = Type.label, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
    }
}

/** "‹  26 Oct – 1 Nov 2026  ›": the dates of the shown period, stepped with the arrows. */
@Composable
private fun StepRow(title: String, canBack: Boolean, canForward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.body.copy(color = Palette.Value), modifier = Modifier.weight(1f))
        if (canBack || canForward) {
            Box(Modifier.clip(RoundedCornerShape(20.dp)).semantics { contentDescription = "Previous" }.pressable(enabled = canBack, onClick = onBack).padding(10.dp)) {
                IconChevron(if (canBack) Palette.Accent else Palette.Hairline, left = true, size = 22.dp)
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.clip(RoundedCornerShape(20.dp)).semantics { contentDescription = "Next" }.pressable(enabled = canForward, onClick = onForward).padding(10.dp)) {
                IconChevron(if (canForward) Palette.Accent else Palette.Hairline, left = false, size = 22.dp)
            }
        }
    }
}

private fun valueOf(s: Summary, m: Metric): Double? = when (m) {
    Metric.Distance -> s.distanceKm
    Metric.Fuel -> s.fuelL
    Metric.Cost -> s.cost
    Metric.Economy -> s.kmPerL
    Metric.Speed -> s.avgSpeed
}

/**
 * One bar per day (or hour, week, month) of the period, empty ones included. Tapping a bar selects
 * it: a soft column of the accent colour rises behind it, brightest at the base, the other bars dim,
 * and its date and figures show above the chart. Tapping it again clears the selection.
 */
@Composable
private fun SlotChart(slots: List<com.abeernch.civicobd.core.Slot>, trips: List<TripRow>, metric: Metric, selected: Int?, onSelect: (Int) -> Unit) {
    val chartHeight = 150.dp
    val sums = remember(slots, trips) { slots.map { sl -> Summary.of(trips.filter { it.startMs >= sl.startMs && it.startMs < sl.endMs }) } }
    val values = sums.map { valueOf(it, metric)?.takeIf { v -> v > 0 } }
    val max = values.maxOfOrNull { it ?: 0.0 }?.takeIf { it > 0 } ?: 1.0
    val decimals = if (metric == Metric.Cost) 0 else 1
    val now = System.currentTimeMillis()
    val current = slots.indexOfFirst { now >= it.startMs && now < it.endMs }
    val gap = if (slots.size > 16) 2.dp else 6.dp

    Column(Modifier.padding(horizontal = 20.dp)) {
        // Readout: the selected bar, or the period as a whole.
        val sel = selected?.let { slots.getOrNull(it) }
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sel != null) {
                val s = sums[selected]
                Column(Modifier.weight(1f)) {
                    Text(sel.longLabel, style = Type.label)
                    Text(
                        "${fmt(valueOf(s, metric), decimals)} ${metric.unit}" +
                            (if (metric != Metric.Economy && s.kmPerL != null) "   ·   ${fmt(s.kmPerL)} km/L" else "") +
                            (if (s.trips > 0) "   ·   ${s.trips} drive" + (if (s.trips > 1) "s" else "") else ""),
                        style = Type.body.copy(color = Palette.Accent),
                    )
                }
            } else {
                Text("Tap a bar for its figures", style = Type.label)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(chartHeight + 20.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.Bottom,
        ) {
            slots.forEachIndexed { index, _ ->
                val value = values[index]
                val target = chartHeight * ((value ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
                val barHeight by animateDpAsState(target, Motion.glide(), label = "bar")
                val on = index == selected
                val glow by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, Motion.fade(260), label = "glow")
                val accent = Palette.Accent
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // The selection column, drawn behind without clipping, so the value above
                        // the bar can be wider than the bar's own narrow column.
                        .drawBehind {
                            drawRoundRect(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(accent.copy(alpha = 0f), accent.copy(alpha = 0.10f * glow), accent.copy(alpha = 0.26f * glow))
                                ),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                            )
                        }
                        .pressable { onSelect(index) },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    val color = when {
                        on -> Palette.Accent
                        selected != null -> Palette.Value.copy(alpha = 0.28f)
                        index == current -> Palette.Accent
                        else -> Palette.Value.copy(alpha = 0.85f)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (on && value != null) {
                            Text(
                                fmt(value, decimals),
                                style = Type.label.copy(fontSize = Type.sz(12f), color = Palette.Accent),
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                            )
                            Spacer(Modifier.height(3.dp))
                        }
                        // Slim bars, never wider than 26 dp; an empty day keeps a faint stub.
                        Box(
                            Modifier
                                .widthIn(max = 26.dp)
                                .fillMaxWidth()
                                .height(if (value == null) 2.dp else barHeight.coerceAtLeast(3.dp))
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(if (value == null) Palette.Hairline else color)
                        )
                    }
                    // A short accent line under the selected bar.
                    if (on) Box(Modifier.fillMaxWidth().height(3.dp).background(accent))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            slots.forEachIndexed { index, sl ->
                Text(
                    sl.label,
                    style = Type.label.copy(
                        fontSize = Type.sz(12f),
                        color = if (index == selected) Palette.Accent else Palette.Label,
                    ),
                    // Narrow day columns (a month has 31): the label may spill into the empty columns beside it.
                    modifier = Modifier.weight(1f).wrapContentWidth(unbounded = true),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * A choice shown as its current value with a small down arrow; tapping opens a menu of the others.
 * Takes the place of a scrolling row of chips, so the choices do not crowd the screen.
 */
@Composable
fun <T> Dropdown(options: List<T>, selected: T, label: (T) -> String, style: androidx.compose.ui.text.TextStyle, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).pressable { open = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label(selected), style = style)
            Spacer(Modifier.width(8.dp))
            IconChevronDown(Palette.Accent, (style.fontSize.value * 0.75f).dp)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Palette.Surface),
        ) {
            options.forEach { o ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            label(o),
                            style = Type.body.copy(color = if (o == selected) Palette.Accent else Palette.Value),
                        )
                    },
                    onClick = { open = false; onPick(o) },
                )
            }
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
