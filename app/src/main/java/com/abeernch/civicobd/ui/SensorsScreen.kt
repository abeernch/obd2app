package com.abeernch.civicobd.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
        item {
            val m = live.monitors
            Section("Diagnostics") {
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
                        .clickable(onClick = onShareLog)
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
