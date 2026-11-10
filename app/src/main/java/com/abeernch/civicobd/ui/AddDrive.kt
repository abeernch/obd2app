package com.abeernch.civicobd.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.Car
import com.abeernch.civicobd.core.FuelLog
import com.abeernch.civicobd.core.HandDrives
import com.abeernch.civicobd.core.HandDrives.Distance
import com.abeernch.civicobd.core.LiveBus
import com.abeernch.civicobd.core.TripRow
import com.abeernch.civicobd.core.TripStore
import com.abeernch.civicobd.core.TripTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val DAY_MS = 86_400_000L

/** A typed number with no trailing zeros, always with a full stop. */
private fun typed(v: Double): String =
    java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

private fun midnightOf(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Whole days from [from] to [to], both midnights (rounded, so a clock change does not shift it). */
private fun daysBetween(from: Long, to: Long): Int = ((to - from).toDouble() / DAY_MS).roundToInt()

/**
 * Adding a drive the app did not record, or correcting one added by hand ([editing]). The distance comes
 * from the odometer (what it shows now against what the app expects, or before and after) or is typed in.
 * Fuel is estimated from recent economy unless the litres are entered, and costed at the price paid at the
 * time. [liveTrip] is the drive being recorded, if any. [onDone] gets the saved drive, or null.
 */
@Composable
internal fun AddDriveScreen(
    store: TripStore,
    fuel: FuelLog,
    settings: AppSettings,
    liveTrip: TripTotals,
    editing: TripRow?,
    onDone: (TripRow?) -> Unit,
) {
    BackHandler { onDone(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val opened = remember { System.currentTimeMillis() }
    val today = remember { midnightOf(opened) }
    val live = liveTrip.takeIf { it.active }

    // How the distance is given. Reading the dashboard now is offered for a new drive only: it is measured
    // against the odometer the app expects today.
    val modes = if (editing == null) Distance.entries.toList() else listOf(Distance.BeforeAfter, Distance.Kilometres)
    var mode by remember {
        mutableStateOf(
            when {
                editing != null && editing.odoStart != null && editing.odoEnd != null -> Distance.BeforeAfter
                editing != null -> Distance.Kilometres
                settings.odometerKm != null -> Distance.OdometerNow
                else -> Distance.Kilometres
            }
        )
    }
    var reading by remember { mutableStateOf("") }
    var before by remember { mutableStateOf(editing?.odoStart?.let(::typed) ?: "") }
    var after by remember { mutableStateOf(editing?.odoEnd?.let(::typed) ?: "") }
    var kmText by remember { mutableStateOf(editing?.distanceKm?.let(::typed) ?: "") }

    // When: a day (today, or so many days back) and a start time. A new drive starts at the time now, to
    // the five minutes before; a drive being corrected keeps its own.
    val startCal = remember { Calendar.getInstance().apply { timeInMillis = editing?.startMs ?: opened } }
    var dayOffset by remember { mutableIntStateOf(daysBetween(today, midnightOf(startCal.timeInMillis)).coerceAtMost(0)) }
    var hour by remember { mutableStateOf("%02d".format(startCal.get(Calendar.HOUR_OF_DAY))) }
    var minute by remember {
        val m = startCal.get(Calendar.MINUTE)
        mutableStateOf("%02d".format(if (editing != null) m else m / 5 * 5))
    }
    var minutes by remember { mutableStateOf(editing?.driveS?.takeIf { it > 0 }?.let { typed(it / 60) } ?: "") }
    var litres by remember { mutableStateOf(editing?.takeIf { !it.fuelEstimated && it.fuelL > 0 }?.fuelL?.let(::typed) ?: "") }
    var note by remember { mutableStateOf(editing?.note ?: "") }

    val h = hour.toIntOrNull()?.takeIf { it in 0..23 }
    val m = minute.toIntOrNull()?.takeIf { it in 0..59 }
    val dayStart = remember(dayOffset) { Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_MONTH, dayOffset) }.timeInMillis }
    val startMs = if (h != null && m != null) dayStart + h * 3_600_000L + m * 60_000L else null
    val mins = minutes.toDoubleOrNull()?.takeIf { it > 0 }
    val timeProblem = startMs?.let { HandDrives.timeProblem(it, mins, System.currentTimeMillis(), live?.startMs) }

    // What the app expects the odometer to show now, and the economy and price to cost the drive with.
    var expected by remember { mutableStateOf<Double?>(null) }
    var recentKmPerL by remember { mutableStateOf<Double?>(null) }
    var price by remember { mutableStateOf(settings.fuelPrice) }
    LaunchedEffect(Unit) {
        expected = withContext(Dispatchers.IO) {
            Car.status(settings, store, live?.distanceKm ?: 0.0, live?.id ?: -1).odometerKm
        }
    }
    LaunchedEffect(startMs ?: dayStart) {
        val at = startMs ?: dayStart
        val (k, p) = withContext(Dispatchers.IO) {
            // Neither this drive itself nor the one being recorded.
            val others = { from: Long, to: Long -> store.between(from, to).filter { it.id != editing?.id && it.id != liveTrip.id } }
            HandDrives.recentKmPerL(others(at - 30 * DAY_MS, at), others(0, at)) to HandDrives.priceAt(fuel.all(), at, settings.fuelPrice)
        }
        recentKmPerL = k
        price = p
    }

    val km: Double? = when (mode) {
        Distance.OdometerNow -> reading.toDoubleOrNull()?.let { r -> expected?.let { HandDrives.missingKm(r, it) } }
        Distance.BeforeAfter -> before.toDoubleOrNull()?.let { b -> after.toDoubleOrNull()?.let { a -> HandDrives.between(b, a) } }
        Distance.Kilometres -> kmText.toDoubleOrNull()?.takeIf { it >= 0.1 && it <= HandDrives.MAX_KM }
    }
    val typedL = litres.toDoubleOrNull()?.takeIf { it > 0 }
    val litresBad = typedL != null && typedL > 120
    val estimatedL = if (km != null) recentKmPerL?.let { HandDrives.estimateFuel(km, it) } else null
    // What stands in the way of saving, first thing first; the button says it.
    val problem = when {
        km == null -> "Enter the distance"
        startMs == null -> "Enter the start time"
        timeProblem != null -> "Set an earlier time"
        litresBad -> "Check the litres"
        else -> null
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Back", style = Type.body.copy(color = Palette.Accent),
                    modifier = Modifier.pressable { onDone(null) }.padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                )
                Text(if (editing == null) "Add a drive" else "Correct this drive", style = Type.title)
            }
        }
        item {
            Text(
                "For a drive the app did not record. Its distance counts towards your totals, the odometer and the " +
                    "tank. Its fuel is an estimate, so it is left out of economy, speed and route comparisons.",
                style = Type.label,
            )
        }

        // ---- distance
        item {
            Column {
                Text("Distance from", style = Type.label)
                Spacer(Modifier.height(8.dp))
                Chips(modes, mode, {
                    when (it) {
                        Distance.OdometerNow -> "Odometer now"
                        Distance.BeforeAfter -> "Before and after"
                        Distance.Kilometres -> "Kilometres"
                    }
                }, inset = 0.dp) { mode = it }
                Spacer(Modifier.height(4.dp))
                when (mode) {
                    Distance.OdometerNow -> {
                        val exp = expected
                        if (settings.odometerKm == null) {
                            Text(
                                "This needs one earlier odometer reading. Enter it in Settings, under Car, or use Before and after.",
                                style = Type.label.copy(color = Palette.Caution), modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            NumberField("What the odometer shows", reading, "km") { reading = it }
                            val r = reading.toDoubleOrNull()
                            val shown = exp?.let { "%,d".format(it.roundToInt()) }
                            Text(
                                when {
                                    exp == null -> "Working out what the app expects..."
                                    r == null -> "The app expects about $shown km."
                                    km != null -> "The app expects about $shown km, so ${fmt(km, 1)} km were not recorded."
                                    r - exp > HandDrives.MAX_KM -> "That is more than ${"%,d".format(HandDrives.MAX_KM.toInt())} km past the $shown km the app expects. Check the reading."
                                    else -> "That is no more than the app has already counted ($shown km), so there is nothing to add."
                                },
                                style = Type.label.copy(color = if (r != null && km == null && exp != null) Palette.Caution else Palette.Label),
                            )
                        }
                    }
                    Distance.BeforeAfter -> {
                        NumberField("Odometer before", before, "km") { before = it }
                        NumberField("Odometer after", after, "km") { after = it }
                        if (before.toDoubleOrNull() != null && after.toDoubleOrNull() != null) {
                            Text(
                                if (km != null) "${fmt(km, 1)} km"
                                else "After should be more than before, and no more than ${"%,d".format(HandDrives.MAX_KM.toInt())} km on.",
                                style = Type.label.copy(color = if (km == null) Palette.Caution else Palette.Label),
                            )
                        }
                    }
                    Distance.Kilometres -> NumberField("Distance", kmText, "km") { kmText = it }
                }
            }
        }

        // ---- when
        item {
            Column {
                Text("When", style = Type.label)
                DayStepper(dayStart, today, canForward = dayOffset < 0, onBack = { if (dayOffset > -366) dayOffset-- }, onForward = { dayOffset++ })
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Started at", style = Type.label.copy(fontSize = Type.body.fontSize), modifier = Modifier.weight(1f))
                    TwoDigits(hour, "Hour") { hour = it }
                    Text("  :  ", style = Type.body)
                    TwoDigits(minute, "Minute") { minute = it }
                }
                NumberField("Time driving (optional)", minutes, "min") { minutes = it }
                timeProblem?.let { Text(it, style = Type.label.copy(color = Palette.Caution)) }
            }
        }

        // ---- fuel and cost
        item {
            Column {
                NumberField("Litres, if you know them", litres, "L") { litres = it }
                Text(
                    when {
                        litresBad -> "That is more than a tank holds. Check the litres."
                        typedL != null -> "Fuel as entered" + (km?.let { ", ${fmt(it / typedL, 1)} km/L" } ?: "") +
                            ". ${rupees(typedL * price)} at ${rupees(price)} a litre."
                        km == null -> "The fuel is estimated from your recent economy once the distance is in."
                        estimatedL != null -> "Fuel estimated at ${fmt(estimatedL, 1)} L from your recent ${fmt(recentKmPerL, 1)} km/L, " +
                            "${rupees(estimatedL * price)} at ${rupees(price)} a litre."
                        else -> "No recorded drives yet to estimate the fuel from. Enter the litres if you know them."
                    },
                    style = Type.label.copy(color = if (litresBad) Palette.Caution else Palette.Label),
                )
            }
        }

        // ---- note
        item {
            Column {
                Text("Note (optional)", style = Type.label)
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Palette.Hairline, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (note.isEmpty()) Text("Who drove, or why it was not recorded", style = Type.label)
                    BasicTextField(
                        value = note,
                        onValueChange = { note = it.take(120) },
                        singleLine = true,
                        textStyle = Type.body.copy(color = Palette.Value),
                        cursorBrush = SolidColor(Palette.Accent),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Note" },
                    )
                }
            }
        }

        item {
            OutlineButton(
                problem ?: ((if (editing == null) "Save drive  " else "Save changes  ") + "${fmt(km, 1)} km"),
                color = if (problem == null) Palette.Accent else Palette.Label,
            ) {
                if (problem != null || km == null || startMs == null) return@OutlineButton
                val odoStart = if (mode == Distance.BeforeAfter) before.toDoubleOrNull() else null
                val odoEnd = if (mode == Distance.BeforeAfter) after.toDoubleOrNull() else null
                val trip = HandDrives.build(
                    id = editing?.id ?: -1, startMs = startMs, km = km, minutes = mins,
                    litres = typedL, estimatedL = estimatedL, pricePerL = price, note = note,
                    odoStart = odoStart, odoEnd = odoEnd,
                    shared = editing?.shared ?: 0.0, passengers = editing?.passengers ?: 0,
                )
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        if (editing == null) trip.copy(id = store.insert(trip)) else trip.also { store.updateManual(it) }
                    }
                    HandDrives.odometerToKeep(
                        mode, reading.toDoubleOrNull(), odoEnd, trip.startMs, trip.endMs,
                        System.currentTimeMillis(), settings.odometerAtMs, LiveBus.state.value.trip,
                    )?.let { (odo, at) ->
                        settings.odometerKm = odo
                        settings.odometerAtMs = at
                    }
                    LiveBus.tripsChanged()
                    com.abeernch.civicobd.widget.TripWidget.refresh(context)
                    onDone(saved)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** "Today, Tue 10 Nov" with arrows to step back a day, never into the future. */
@Composable
private fun DayStepper(dayStart: Long, today: Long, canForward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
    val name = SimpleDateFormat("EEE d MMM", Locale.US).format(Date(dayStart))
    val label = when (daysBetween(dayStart, today)) {
        0 -> "Today, $name"
        1 -> "Yesterday, $name"
        else -> name
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.body.copy(color = Palette.Value), modifier = Modifier.weight(1f))
        Box(Modifier.clip(RoundedCornerShape(20.dp)).semantics { contentDescription = "Day before" }.pressable(onClick = onBack).padding(10.dp)) {
            IconChevron(Palette.Accent, left = true, size = 22.dp)
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.clip(RoundedCornerShape(20.dp)).semantics { contentDescription = "Day after" }.pressable(enabled = canForward, onClick = onForward).padding(10.dp)) {
            IconChevron(if (canForward) Palette.Accent else Palette.Hairline, left = false, size = 22.dp)
        }
    }
}

/** Two digits, for an hour or a minute. */
@Composable
private fun TwoDigits(value: String, name: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(2)) },
        singleLine = true,
        textStyle = Type.body.copy(textAlign = TextAlign.Center, color = Palette.Value),
        cursorBrush = SolidColor(Palette.Accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .width(56.dp)
            .border(1.dp, Palette.Hairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { contentDescription = name },
    )
}
