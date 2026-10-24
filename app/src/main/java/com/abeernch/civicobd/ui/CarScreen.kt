package com.abeernch.civicobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.CarEvent
import com.abeernch.civicobd.core.CarStatus
import com.abeernch.civicobd.core.Diagnostics
import com.abeernch.civicobd.core.Link
import com.abeernch.civicobd.core.LiveState
import com.abeernch.civicobd.core.Maintenance
import com.abeernch.civicobd.core.ServiceStatus
import com.abeernch.civicobd.core.Summary
import com.abeernch.civicobd.core.Tank

/**
 * The car's condition in one place: anything that needs attention first, then service jobs,
 * fuel tank, fault codes, battery and readiness. Live readings sit folded at the bottom.
 */
@Composable
fun CarScreen(
    live: LiveState,
    car: CarStatus,
    tank: Tank?,
    settings: AppSettings,
    starts: List<CarEvent>,
    today: Summary,
    week: Summary,
    total: Summary,
    onOpenFuel: () -> Unit,
    onServiceChanged: () -> Unit,
) {
    var tick by remember { mutableStateOf(0) }
    val statuses = remember(tick, car.odometerKm) { Maintenance.statuses(settings, car.odometerKm) }
    // The list itself keeps a fixed order, so rows never move while you edit one.
    val rows = remember(tick, car.odometerKm) { Maintenance.load(settings).map { Maintenance.status(it, car.odometerKm) } }
    val diag by Diagnostics.state.collectAsState()
    var liveOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // One line, only when something needs attention.
        val mil = live.monitors?.mil == true
        val due = statuses.filter { it.overdue || it.soon }
        val alert = when {
            mil || diag.stored.isNotEmpty() ->
                "Check-engine light" + (diag.stored.firstOrNull()?.let { ": ${it.code}, ${it.description}" } ?: " is on") to Palette.Warning
            diag.pending.isNotEmpty() -> "Pending fault code ${diag.pending.first().code}: ${diag.pending.first().description}" to Palette.Caution
            due.isNotEmpty() -> due.joinToString(", ") { "${it.item.name} ${Maintenance.dueText(it)}" } to
                (if (due.any { it.overdue }) Palette.Warning else Palette.Caution)
            else -> null
        }
        alert?.let { (text, color) -> AlertLine(text, color) }

        Column(Modifier.padding(horizontal = 20.dp)) {
            Section("Service") {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(
                        car.odometerKm?.let { "%,d".format(it.toLong()) } ?: "--",
                        style = Type.value.copy(fontSize = Type.sz(30f), lineHeight = Type.sz(34f)),
                    )
                    Text("  km odometer", style = Type.label, modifier = Modifier.padding(bottom = 4.dp))
                }
                if (car.odometerKm == null) {
                    Text(
                        "Enter the odometer once in Settings > Car, and distance-based reminders start counting.",
                        style = Type.label,
                    )
                }
                rows.forEach { st ->
                    androidx.compose.runtime.key(st.item.key) {
                    ServiceRow(st, car.odometerKm) { updated ->
                        Maintenance.save(settings, Maintenance.load(settings).map { if (it.key == updated.key) updated else it })
                        tick++
                        onServiceChanged()
                    }
                    }
                }
            }
        }

        TankCard(tank, onOpenFuel)

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            FaultCodes(live.link == Link.Live)
            BatteryHealth(starts)
            Readiness(live)
            Section("Live readings") {
                Text(
                    if (liveOpen) "Hide all readings" else "Show all readings",
                    style = Type.body.copy(color = Palette.Accent),
                    modifier = Modifier.pressable { liveOpen = !liveOpen }.padding(vertical = 10.dp),
                )
                if (liveOpen) LiveData(live, today, week, total)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AlertLine(text: String, color: Color) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = 12.dp).width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(text, style = Type.body.copy(color = Palette.Value))
    }
}

/**
 * Colour for how much of a job's interval is left: green with plenty left, through yellow and
 * orange, to red when due. The same red-yellow-green scale as the route maps.
 */
private fun lifeColor(st: ServiceStatus): Color {
    val life = st.life ?: return Palette.Label
    return Color(com.abeernch.civicobd.core.Route.color(com.abeernch.civicobd.core.ColorScale.RedYellowGreen, life.toFloat().coerceIn(0f, 1f)))
}

/**
 * One service job: name, when it is due, and a bar of what is left. Tap to edit. Values are typed
 * into the fields and only saved with Save (or Done today), so nothing changes while typing.
 */
@Composable
private fun ServiceRow(st: ServiceStatus, odometerKm: Double?, onChange: (com.abeernch.civicobd.core.ServiceItem) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val color = lifeColor(st)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().pressable { open = !open }.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st.item.name, style = Type.body, modifier = Modifier.weight(1f))
                Text(
                    Maintenance.dueText(st),
                    style = Type.label.copy(color = when { st.badReading -> Palette.Warning; st.known -> color; else -> Palette.Label }),
                )
            }
            st.life?.let { life ->
                // Overdue jobs show a full red bar rather than an empty one.
                val fill = if (st.overdue) 1f else life.toFloat().coerceIn(0.02f, 1f)
                Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Hairline)) {
                    Box(Modifier.fillMaxWidth(fill).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
                }
            }
        }
        if (open) {
            val item = st.item
            var every by remember { mutableStateOf(item.everyKm?.let { fmt(it, 0) } ?: "") }
            var months by remember { mutableStateOf(item.everyMonths?.toString() ?: "") }
            var doneAt by remember { mutableStateOf(item.doneKm?.let { fmt(it, 0) } ?: "") }
            var problem by remember { mutableStateOf<String?>(null) }
            NumberField("Every", every, "km") { every = it; problem = null }
            NumberField("Or every", months, "months") { months = it; problem = null }
            NumberField("Last done at", doneAt, "km") { doneAt = it; problem = null }
            item.doneMs?.let {
                Text(
                    "Last done " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(java.util.Date(it)),
                    style = Type.label,
                )
            }
            problem?.let { Text(it, style = Type.label.copy(color = Palette.Warning), modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(8.dp))
            OutlineButton("Save", color = Palette.Accent) {
                val km = doneAt.toDoubleOrNull()
                problem = when {
                    km != null && odometerKm != null && km > odometerKm + 50 ->
                        "That is more than the odometer (" + "%,d".format(odometerKm.toLong()) + " km). Check the number."
                    km != null && km < 0 -> "Enter a reading of 0 or more."
                    else -> null
                }
                if (problem == null) {
                    onChange(
                        item.copy(
                            everyKm = every.toDoubleOrNull()?.takeIf { it > 0 },
                            everyMonths = months.toDoubleOrNull()?.toInt()?.takeIf { it > 0 },
                            doneKm = km,
                        )
                    )
                    open = false
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlineButton(
                if (odometerKm != null) "Done today, at " + "%,d".format(odometerKm.toLong()) + " km" else "Done today",
                color = Palette.Label,
            ) {
                onChange(
                    item.copy(
                        everyKm = every.toDoubleOrNull()?.takeIf { it > 0 } ?: item.everyKm,
                        everyMonths = months.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: item.everyMonths,
                        doneKm = odometerKm ?: item.doneKm,
                        doneMs = System.currentTimeMillis(),
                    )
                )
                open = false
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun Readiness(live: LiveState) {
    val m = live.monitors
    Section("Readiness monitors") {
        if (m == null) {
            ReadingRow("Monitor status", "read when the car connects")
            return@Section
        }
        ReadingRow(
            "Check-engine light", if (m.mil) "ON" else "off", "",
            color = if (m.mil) Palette.Warning else Palette.Value,
        )
        m.list.filter { it.available }.forEach { mon ->
            ReadingRow(
                mon.name, if (mon.complete) "complete" else "incomplete", "",
                color = if (!mon.complete) Palette.Caution else Palette.Value,
            )
        }
    }
}
