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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abeernch.civicobd.core.Channel
import com.abeernch.civicobd.core.RecordingRow
import com.abeernch.civicobd.core.Recordings
import com.abeernch.civicobd.core.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportKind(val label: String) {
    Vertical("CSV, one reading per row (Car Scanner #1)"),
    Table("CSV, one row per second"),
}

/** Charts for one recording: pick parameters, plot together or apart, tap for values, export. */
@Composable
internal fun RecordingView(
    recordings: Recordings,
    rec: RecordingRow,
    onBack: () -> Unit,
    onExport: (RecordingRow, List<Series>, ExportKind) -> Unit,
    onDelete: () -> Unit,
    onMap: () -> Unit,
    header: @Composable () -> Unit = {},
) {
    var channels by remember { mutableStateOf(emptyList<Channel>()) }
    val loaded = remember { mutableStateOf(mapOf<Long, Series>()) }
    val picked = remember { mutableStateListOf<Long>() }
    var combined by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(rec.id) {
        val list = withContext(Dispatchers.IO) { recordings.channels(rec.id) }
        channels = list
        // Start with the three most useful parameters, if present.
        val wanted = listOf("Vehicle speed", "Calculated instant fuel consumption", "Average fuel consumption", "Engine RPM")
        wanted.mapNotNull { w -> list.firstOrNull { it.name == w } }.take(3).forEach { picked += it.id }
    }
    // Load samples for newly picked parameters.
    LaunchedEffect(picked.toList()) {
        val missing = picked.filter { it !in loaded.value }
        if (missing.isNotEmpty()) {
            val fresh = withContext(Dispatchers.IO) {
                missing.mapNotNull { id -> channels.firstOrNull { it.id == id } }
                    .associate { ch -> ch.id to recordings.series(rec.id, ch) }
            }
            loaded.value = loaded.value + fresh
        }
    }

    val view = remember(rec.id) { ChartView(rec.startMs, rec.endMs) }
    val shown = picked.mapNotNull { loaded.value[it] }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Back", style = Type.body.copy(color = Palette.Accent),
                    modifier = Modifier.pressable(onClick = onBack).padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                )
                Text(
                    SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(rec.startMs)),
                    style = Type.title, modifier = Modifier.weight(1f),
                )
                Text(
                    if (view.zoomed) "Zoom out" else "",
                    style = Type.label.copy(color = Palette.Accent),
                    modifier = Modifier.pressable { view.reset() }.padding(4.dp),
                )
            }
        }
        item { header() }
        if (channels.any { it.name.equals("Latitude", ignoreCase = true) || it.name.contains("Latitude") }) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    OutlineButton("Route map", color = Palette.Accent, onClick = onMap)
                }
            }
        }
        item {
            Chips(listOf(false, true), combined, { if (it) "One plot" else "Separate plots" }) { combined = it }
        }
        item {
            // Marker readout: time and every plotted value at the marker
            Readout(shown, view, combined)
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    "Pick parameters below to plot them.",
                    style = Type.label, modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        } else if (combined) {
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    LineChart(shown, SeriesColors, view, normalised = true, height = 260.dp)
                    Spacer(Modifier.height(4.dp))
                    Text("Each line uses its own scale. Tap for values, pinch to zoom.", style = Type.label)
                }
            }
        } else {
            items(shown, key = { it.channel.id }) { s ->
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(s.channel.label, style = Type.label)
                    Spacer(Modifier.height(6.dp))
                    LineChart(listOf(s), listOf(Palette.Value), view, normalised = false, height = 150.dp)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Section("Parameters  ${picked.size} of ${channels.size}") {
                    Spacer(Modifier.height(6.dp))
                    channels.forEach { ch ->
                        val index = picked.indexOf(ch.id)
                        val on = index >= 0
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .pressable { if (on) picked.remove(ch.id) else picked.add(ch.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val dot = when {
                                !on -> Color.Transparent
                                combined -> SeriesColors[index % SeriesColors.size]
                                else -> Palette.Value
                            }
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, if (on) dot else Palette.Label, CircleShape)
                                    .background(dot)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(ch.name, style = Type.body.copy(color = if (on) Palette.Value else Palette.Label), modifier = Modifier.weight(1f))
                            Text(ch.unit, style = Type.label)
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton(if (menu) "Choose a format" else "Export") { menu = !menu }
                if (menu) {
                    ExportKind.entries.forEach { kind ->
                        OutlineButton(kind.label) {
                            menu = false
                            onExport(rec, emptyList(), kind) // all parameters, loaded by the activity
                        }
                    }
                }
                OutlineButton(
                    if (confirmDelete) "Tap again to delete this drive" else "Delete this drive",
                    color = if (confirmDelete) Palette.Warning else Palette.Label,
                ) {
                    if (confirmDelete) onDelete() else confirmDelete = true
                }
            }
        }
    }
}

@Composable
private fun Readout(shown: List<Series>, view: ChartView, combined: Boolean) {
    val c = view.cursor
    Column(Modifier.padding(horizontal = 20.dp)) {
        if (c == null || shown.isEmpty()) {
            Text("Tap a chart to read values", style = Type.label)
            return@Column
        }
        Text(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(c)), style = Type.body.copy(color = Palette.Accent))
        shown.forEachIndexed { i, s ->
            val j = s.indexAt(c)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val dot = if (combined) SeriesColors[i % SeriesColors.size] else Palette.Value
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(s.channel.name, style = Type.label.copy(fontSize = Type.sz(15f)), modifier = Modifier.weight(1f))
                Text(if (j >= 0) compact(s.v[j]) else "--", style = Type.body)
                Text("  ${s.channel.unit}", style = Type.label)
            }
        }
    }
}

@Composable
fun OutlineButton(text: String, color: Color = Palette.Value, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(10.dp))
            .pressable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, style = Type.body.copy(color = color))
    }
}
