package com.abeernch.civicobd.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abeernch.civicobd.obd.Pids

// Type scale: 12 / 14 / 18 / 40. Tabular figures keep digits from jumping sideways.
private val label = TextStyle(fontFamily = B612, fontSize = 12.sp, color = Palette.Label, letterSpacing = 0.2.sp)
private val body = TextStyle(fontFamily = B612, fontSize = 14.sp, color = Palette.Value)
private val heading = TextStyle(fontFamily = B612, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Value)
private val big = TextStyle(
    fontFamily = B612, fontSize = 40.sp, fontWeight = FontWeight.Bold,
    color = Palette.Value, fontFeatureSettings = "tnum",
)
private val mono = TextStyle(fontFamily = B612Mono, fontSize = 11.sp, color = Palette.Label, lineHeight = 16.sp)

@Composable
fun ProbeScreen(
    state: ProbeState,
    onSelect: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onShare: () -> Unit,
) {
    val busy = state.phase == Phase.Connecting || state.phase == Phase.Initialising
    val statusColor by animateColorAsState(
        when (state.phase) {
            Phase.Live -> Palette.Target
            Phase.Failed -> Palette.Caution
            Phase.Idle -> Palette.Label
            else -> Palette.Value
        },
        animationSpec = tween(400),
        label = "status",
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        // Header: name on the left, live status on the right
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Civic OBD", style = heading, modifier = Modifier.weight(1f))
                StatusDot(statusColor, breathing = busy)
                Spacer(Modifier.width(8.dp))
                Crossfade(state.message, animationSpec = tween(300), label = "message") { msg ->
                    Text(msg, style = label.copy(color = statusColor), maxLines = 1)
                }
            }
        }

        // Adapter choice, then one action button
        item {
            Section("Adapter") {
                if (state.devices.isEmpty()) {
                    Text("No paired devices yet", style = body.copy(color = Palette.Label))
                }
                state.devices.forEach { d ->
                    DeviceRow(d, selected = d.address == state.selected, onClick = { onSelect(d.address) })
                }
                Spacer(Modifier.height(12.dp))
                // Anything but Idle means a connection job is running (live, connecting or retrying).
                val connected = state.phase != Phase.Idle
                ActionButton(
                    text = if (connected) "Disconnect" else "Connect",
                    accent = !connected,
                    enabled = state.selected != null,
                    onClick = if (connected) onDisconnect else onConnect,
                    icon = { c -> IconPower(c, 18.dp) },
                )
            }
        }

        // What the adapter reported
        item {
            AnimatedVisibility(state.version.isNotEmpty(), enter = fadeIn(tween(400)) + expandVertically()) {
                Section("Link") {
                    InfoRow("Adapter", state.version)
                    InfoRow("Protocol", state.protocol.ifEmpty { "detecting" })
                    InfoRow("Battery", state.voltage.ifEmpty { "reading" })
                    if (state.cycleMs > 0) InfoRow("Poll cycle", "${state.cycleMs} ms")
                }
            }
        }

        // Live values
        item {
            AnimatedVisibility(state.live.isNotEmpty(), enter = fadeIn(tween(500)) + expandVertically()) {
                Section("Live") {
                    val shown = listOf(0x0C, 0x0D, 0x11, 0x0B, 0x05, 0x0F)
                    shown.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            pair.forEach { code -> LiveCell(code, state.live[code], Modifier.weight(1f)) }
                        }
                    }
                    val others = state.live.keys.filter { it !in shown }.sorted()
                    others.forEach { code ->
                        val pid = Pids.BY_CODE[code]
                        InfoRow(pid?.name ?: "PID %02X".format(code), format(state.live[code], pid?.unit))
                    }
                }
            }
        }

        // Supported PIDs as small hex tags
        item {
            AnimatedVisibility(state.supported.isNotEmpty(), enter = fadeIn(tween(400))) {
                Section("Supported by your car") {
                    SupportedTags(state.supported)
                }
            }
        }

        // Raw log, newest first, plus share
        item {
            Section("Log") {
                ActionButton(
                    text = "Share report",
                    accent = false,
                    enabled = state.log.isNotEmpty(),
                    onClick = onShare,
                    icon = { c -> IconShare(c, 18.dp) },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        items(state.log.reversed().take(120)) { line ->
            Text(line, style = mono)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = label)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun DeviceRow(item: DeviceItem, selected: Boolean, onClick: () -> Unit) {
    val lineColor by animateColorAsState(
        if (selected) Palette.Target else Color.Transparent, tween(250), label = "select",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).height(28.dp).background(lineColor))
        Spacer(Modifier.width(12.dp))
        IconBluetooth(if (selected) Palette.Target else Palette.Label, 18.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(item.name, style = body)
            Text(item.address, style = label)
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    accent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val color = when {
        !enabled -> Palette.Hairline
        accent -> Palette.Target
        else -> Palette.Value
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, color, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(color)
        Spacer(Modifier.width(10.dp))
        Text(text, style = body.copy(color = color))
    }
}

@Composable
private fun InfoRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(name, style = label, modifier = Modifier.width(110.dp))
        Text(value, style = body)
    }
}

@Composable
private fun LiveCell(code: Int, value: Double?, modifier: Modifier) {
    val pid = Pids.BY_CODE[code]
    Column(modifier) {
        RollingNumber(format(value, null), big)
        Text("${pid?.name ?: ""}  ${pid?.unit ?: ""}", style = label)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupportedTags(codes: List<Int>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        codes.forEach { code ->
            val known = Pids.BY_CODE.containsKey(code)
            Text(
                "%02X".format(code),
                style = label.copy(color = if (known) Palette.Value else Palette.Label),
                modifier = Modifier
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private fun format(value: Double?, unit: String?): String {
    if (value == null) return "--"
    val number = if (kotlin.math.abs(value) >= 100) "%.0f".format(value) else "%.1f".format(value)
    return if (unit.isNullOrEmpty()) number else "$number $unit"
}
