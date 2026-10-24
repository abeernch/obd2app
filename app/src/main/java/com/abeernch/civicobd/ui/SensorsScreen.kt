package com.abeernch.civicobd.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.LiveState
import com.abeernch.civicobd.core.Summary

/** Every reading the car gives, plus everything computed from them. Grouped, one row each. */
@Composable
fun SensorsScreen(
    live: LiveState,
    today: Summary,
    week: Summary,
    total: Summary,
    onShareLog: () -> Unit,
    starts: List<com.abeernch.civicobd.core.CarEvent> = emptyList(),
) {
    val v = live.values
    val d = live.derived
    val trip = live.trip
    val price = live.fuelPrice

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Section("Fuel") {
                ReadingRow("Instant economy", if (d.fuelCut) "fuel cut" else fmt(d.instantKmL), "km/L")
                ReadingRow("Instant fuel rate", fmt(d.fuelLph, 2), "L/h")
                ReadingRow("Average, last 10 s", fmt(d.avg10KmL), "km/L")
                ReadingRow("Average, trip", fmt(trip.kmPerL), "km/L")
                ReadingRow("Average, today", fmt(today.kmPerL), "km/L")
                ReadingRow("Average, this week", fmt(week.kmPerL), "km/L")
                ReadingRow("Average, all time", fmt(total.kmPerL), "km/L")
                ReadingRow("Fuel used, trip", fmt(trip.fuelL, 2), "L")
                ReadingRow("Fuel used, today", fmt(today.fuelL, 2), "L")
                ReadingRow("Fuel used, this week", fmt(week.fuelL, 2), "L")
                ReadingRow("Fuel used, all time", fmt(total.fuelL, 2), "L")
            }
        }
        item {
            Section("Cost  at ${fmt(price, 0)} PKR/L") {
                ReadingRow("Trip", fmt(trip.fuelL * price, 0), "PKR")
                ReadingRow("Today", fmt(today.cost, 0), "PKR")
                ReadingRow("This week", fmt(week.cost, 0), "PKR")
                ReadingRow("All time", fmt(total.cost, 0), "PKR")
            }
        }
        item {
            Section("Distance and time") {
                ReadingRow("Distance, trip", fmt(trip.distanceKm, 2), "km")
                ReadingRow("Distance, today", fmt(today.distanceKm, 2), "km")
                ReadingRow("Distance, this week", fmt(week.distanceKm, 2), "km")
                ReadingRow("Distance, all time", fmt(total.distanceKm, 2), "km")
                ReadingRow("Average speed, trip", fmt(trip.avgSpeed), "km/h")
                ReadingRow("Top speed, trip", fmt(trip.maxSpeed, 0), "km/h")
                ReadingRow("Driving time, trip", duration(trip.driveS))
                ReadingRow("Standing time, trip", duration(trip.idleS))
            }
        }
        item {
            Section("Engine") {
                ReadingRow("Engine speed", fmt(v[0x0C], 0), "rpm")
                ReadingRow("Vehicle speed", fmt(v[0x0D], 0), "km/h")
                ReadingRow("Gear (estimated)", if (d.gear == 0) "N" else d.gear.toString())
                ReadingRow("Calculated engine load", fmt(v[0x04], 0), "%")
                ReadingRow("Throttle position", fmt(v[0x11], 1), "%")
                ReadingRow("Throttle, from closed", fmt(d.throttleRel, 0), "%")
                ReadingRow("Intake manifold pressure", fmt(v[0x0B], 0), "kPa")
                ReadingRow("Intake manifold pressure", fmt(v[0x0B]?.let { it * 0.145038 }, 2), "psi")
                ReadingRow("Boost (negative is vacuum)", fmt(d.boostKpa?.let { it * 0.145038 }, 2), "psi")
                ReadingRow("Timing advance", fmt(v[0x0E], 1), "°")
                ReadingRow("Coolant temperature", fmt(v[0x05], 0), "°C")
                ReadingRow("Intake air temperature", fmt(v[0x0F], 0), "°C")
                ReadingRow("Battery voltage", fmt(live.voltage), "V")
                ReadingRow("Acceleration", fmt(d.accelG, 2), "g")
                ReadingRow("Engine power (estimated)", fmt(d.powerHp, 0), "hp")
                ReadingRow("Fuel cut active", if (d.fuelCut) "yes" else "no")
            }
        }
        item { FaultCodes(live.link == com.abeernch.civicobd.core.Link.Live) }
        item { BatteryHealth(starts) }
        item {
            val m = live.monitors
            Section("Readiness monitors") {
                if (m == null) {
                    ReadingRow("Monitor status", "not read yet")
                } else {
                    ReadingRow(
                        "Check-engine light", if (m.mil) "ON" else "off", "",
                        color = if (m.mil) Palette.Warning else Palette.Value,
                    )
                    ReadingRow(
                        "Stored fault codes", m.dtcCount.toString(), "",
                        color = if (m.dtcCount > 0) Palette.Caution else Palette.Value,
                    )
                    m.list.forEach { mon ->
                        val text = when {
                            !mon.available -> "not fitted"
                            mon.complete -> "complete"
                            else -> "incomplete"
                        }
                        ReadingRow(
                            mon.name, text, "",
                            color = if (mon.available && !mon.complete) Palette.Caution else Palette.Value,
                        )
                    }
                }
            }
        }
        item {
            Section("Connection") {
                ReadingRow("Status", live.status)
                ReadingRow("Adapter", live.adapter.ifEmpty { "--" })
                ReadingRow("Protocol", live.protocol.ifEmpty { "--" })
                ReadingRow("Refresh cycle", if (live.cycleMs > 0) live.cycleMs.toString() else "--", "ms")
                ReadingRow("Supported PIDs", live.supported.joinToString(" ") { "%02X".format(it) }.ifEmpty { "--" })
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Palette.Hairline, RoundedCornerShape(10.dp))
                        .pressable(onClick = onShareLog)
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconShare(Palette.Value, 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Share connection log", style = Type.body)
                }
            }
        }
        items(live.log.reversed().take(60)) { line -> Text(line, style = Type.mono) }
    }
}


/** Stored and pending fault codes with plain-language meanings, the freeze frame, and a guarded clear. */
@Composable
private fun FaultCodes(connected: Boolean) {
    val diag by com.abeernch.civicobd.core.Diagnostics.state.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    Section("Fault codes") {
        if (diag.readAtMs == 0L) {
            ReadingRow("Last read", "not yet")
        } else {
            ReadingRow("Last read", java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.US).format(java.util.Date(diag.readAtMs)))
        }
        if (diag.stored.isEmpty() && diag.pending.isEmpty() && diag.readAtMs > 0) {
            ReadingRow("Stored codes", "none")
        }
        diag.stored.forEach { CodeRow(it, Palette.Warning, "stored") }
        diag.pending.forEach { CodeRow(it, Palette.Caution, "pending") }
        if (diag.freezeFrame.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Freeze frame" + (diag.freezeCode?.let { ", saved with $it" } ?: ""), style = Type.section)
            val f = diag.freezeFrame
            f[0x0C]?.let { ReadingRow("Engine speed", fmt(it, 0), "rpm") }
            f[0x0D]?.let { ReadingRow("Vehicle speed", fmt(it, 0), "km/h") }
            f[0x05]?.let { ReadingRow("Coolant", fmt(it, 0), "°C") }
            f[0x04]?.let { ReadingRow("Engine load", fmt(it, 0), "%") }
            f[0x0B]?.let { ReadingRow("Manifold pressure", fmt(it, 0), "kPa") }
            f[0x11]?.let { ReadingRow("Throttle", fmt(it, 0), "%") }
            f[0x0F]?.let { ReadingRow("Intake air", fmt(it, 0), "°C") }
            f[0x0E]?.let { ReadingRow("Timing advance", fmt(it, 1), "°") }
        }
        diag.message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = Type.label.copy(color = Palette.Value))
        }
        Spacer(Modifier.height(12.dp))
        OutlineButton(
            when {
                !connected -> "Connect to the car to read codes"
                diag.busy -> "Reading..."
                else -> "Read codes now"
            },
            color = if (connected) Palette.Accent else Palette.Label,
        ) {
            if (connected && !diag.busy) com.abeernch.civicobd.core.Diagnostics.request(com.abeernch.civicobd.core.DiagRequest.Read)
        }
        if (connected && (diag.stored.isNotEmpty() || diag.pending.isNotEmpty())) {
            Spacer(Modifier.height(10.dp))
            OutlineButton(
                if (confirmClear) "Tap again to clear. Engine off, ignition on." else "Clear codes",
                color = if (confirmClear) Palette.Warning else Palette.Label,
            ) {
                if (confirmClear) {
                    confirmClear = false
                    com.abeernch.civicobd.core.Diagnostics.request(com.abeernch.civicobd.core.DiagRequest.Clear)
                } else {
                    confirmClear = true
                }
            }
            Text(
                "Clearing also resets the readiness monitors. Clear only after a repair, or the fault will return.",
                style = Type.label,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun CodeRow(dtc: com.abeernch.civicobd.core.Dtc, color: androidx.compose.ui.graphics.Color, kind: String) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dtc.code, style = Type.value.copy(fontSize = Type.sz(28f), color = color))
            Spacer(Modifier.width(12.dp))
            Text(kind, style = Type.label.copy(color = color))
        }
        Text(dtc.description, style = Type.body)
    }
}

/** Battery voltage at rest before each start, and the lowest voltage while cranking. */
@Composable
private fun BatteryHealth(starts: List<com.abeernch.civicobd.core.CarEvent>) {
    Section("Battery and starts") {
        if (starts.isEmpty()) {
            Text(
                "Recorded at each engine start: the voltage at rest and the lowest voltage while cranking. " +
                    "A healthy battery rests above 12.4 V and cranks above 9.6 V.",
                style = Type.label,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        val f = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.US)
        starts.take(8).forEach { e ->
            val rest = e.a
            val crank = e.b
            val bad = (rest != null && rest < 12.2) || (crank != null && crank < 9.6)
            ReadingRow(
                f.format(java.util.Date(e.timeMs)),
                "rest ${fmt(rest, 1)}  crank ${fmt(crank, 1)}", "V",
                color = if (bad) Palette.Caution else Palette.Value,
            )
        }
    }
}
