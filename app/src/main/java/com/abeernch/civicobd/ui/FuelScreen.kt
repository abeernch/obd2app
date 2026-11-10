package com.abeernch.civicobd.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.FuelLog
import com.abeernch.civicobd.core.Fillup
import com.abeernch.civicobd.core.PetrolPrice
import com.abeernch.civicobd.core.Tank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small card at the top of Stats: fuel left and range. Tap to open the fuel log. */
@Composable
fun TankCard(tank: Tank?, onOpen: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .border(1.dp, Palette.Hairline, RoundedCornerShape(14.dp))
            .pressable(onClick = onOpen)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fuel tank", style = Type.section, modifier = Modifier.weight(1f))
            Text("Fill-ups", style = Type.label.copy(color = Palette.Accent))
        }
        if (tank?.leftL == null) {
            Spacer(Modifier.height(8.dp))
            Text("Log a full tank to see fuel left and range.", style = Type.label)
        } else {
            Row(Modifier.fillMaxWidth()) {
                Tile("Left", fmt(tank.leftL, 0), "L", Modifier.weight(1f))
                Tile("Range", fmt(tank.rangeKm, 0), "km", Modifier.weight(1f))
                Tile("Since fill", fmt(tank.kmPerL), "km/L", Modifier.weight(1f))
            }
        }
    }
}

/** Odometer and oil change reminder, shown under the tank card. */
@Composable
fun CarCard(status: com.abeernch.civicobd.core.CarStatus) {
    if (status.odometerKm == null) return
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp)
            .fillMaxWidth()
            .border(1.dp, Palette.Hairline, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text("Car", style = Type.section)
        val due = status.oilDueInKm
        val oilColor = when {
            due == null -> Palette.Label
            due < 0 -> Palette.Warning
            due < 500 -> Palette.Caution
            else -> Palette.Accent
        }
        // Smaller than the screen's main tiles, so a six-digit odometer and the oil figure sit side by side.
        val big = Type.value.copy(fontSize = Type.sz(30f), lineHeight = Type.sz(34f))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("%,d".format(status.odometerKm.toLong()), style = big, maxLines = 1)
                Text("Odometer  km", style = Type.label, maxLines = 1)
            }
            Column(Modifier.weight(1f)) {
                Text(due?.let { "%,d".format(kotlin.math.abs(it).toLong()) } ?: "--", style = big.copy(color = oilColor), maxLines = 1)
                Text(if (due != null && due < 0) "Oil overdue  km" else "Oil change in  km", style = Type.label, maxLines = 1)
            }
        }
        // Oil life: how much of the change interval is left, in the same colour.
        status.oilLife?.let { life ->
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Hairline)) {
                Box(Modifier.fillMaxWidth(life.toFloat().coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(oilColor))
            }
            Spacer(Modifier.height(4.dp))
            Text("Oil life ${(life * 100).toInt().coerceIn(0, 100)}%", style = Type.label)
        }
    }
}

/** Fill-up log: add a fill, see how the app's fuel count compared with the pump. */
@Composable
fun FuelScreen(
    fuel: FuelLog,
    settings: AppSettings,
    tank: Tank?,
    version: Int,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var fills by remember { mutableStateOf(emptyList<Fillup>()) }
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // The fill-up open for correcting, and what came of the last correction.
    var editing by remember { mutableStateOf<Long?>(null) }
    var historyMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(version) { fills = withContext(Dispatchers.IO) { fuel.all() } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Back", style = Type.body.copy(color = Palette.Accent),
                    modifier = Modifier.pressable(onClick = onBack).padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                )
                Text("Fuel", style = Type.title)
            }
        }
        item {
            Section("Tank  ${fmt(settings.tankL, 0)} L") {
                Row(Modifier.fillMaxWidth()) {
                    Tile("Left", fmt(tank?.leftL, 0), "L", Modifier.weight(1f))
                    Tile("Range", fmt(tank?.rangeKm, 0), "km", Modifier.weight(1f))
                    Tile("Used", fmt(tank?.usedL, 1), "L", Modifier.weight(1f))
                }
                Text(
                    "Fuel correction factor ${fmt(settings.fuelFactor, 3)}. It updates itself at each full tank.",
                    style = Type.label,
                )
            }
        }
        item {
            OutlineButton(if (adding) "Cancel" else "Log a fill-up", color = Palette.Accent) {
                adding = !adding
                editing = null
            }
            AnimatedVisibility(adding, enter = fadeIn(Motion.fade()) + expandVertically(Motion.settle()), exit = fadeOut(Motion.fade(160)) + shrinkVertically(Motion.settle())) {
                FillupForm(settings, onSave = { f ->
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { fuel.add(f, settings) }
                        adding = false
                        // One note at a time: the latest says what happened.
                        historyMessage = null
                        message = when {
                            saved.factorAfter != null ->
                                "Saved. The app counted ${fmt(saved.appLitres, 1)} L since the last full tank, " +
                                    "the pump put in ${fmt(f.litres, 1)} L. Correction is now ${fmt(saved.factorAfter, 3)}."
                            saved.full -> "Saved. The next full tank will calibrate the fuel count."
                            else -> "Saved as a partial fill."
                        }
                        onChanged()
                    }
                })
            }
            message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = Type.label.copy(color = Palette.Value))
            }
        }
        item {
            Column {
                Text(if (fills.isEmpty()) "No fill-ups yet" else "History", style = Type.section)
                if (fills.isNotEmpty()) Text("Tap a fill-up to correct or delete it.", style = Type.label)
                historyMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = Type.label.copy(color = Palette.Value))
                }
            }
        }
        items(fills, key = { it.id }) { f ->
            FillupCard(
                f, open = editing == f.id, settings = settings,
                onToggle = {
                    editing = if (editing == f.id) null else f.id
                    adding = false
                },
                onSave = { edited ->
                    scope.launch {
                        val factorBefore = settings.fuelFactor
                        withContext(Dispatchers.IO) { fuel.update(f, edited, settings) }
                        editing = null
                        message = null
                        historyMessage = "Fill-up corrected." +
                            (if (kotlin.math.abs(settings.fuelFactor - factorBefore) >= 5e-4) " Correction is now ${fmt(settings.fuelFactor, 3)}." else "")
                        onChanged()
                    }
                },
                onDelete = {
                    scope.launch {
                        val change = withContext(Dispatchers.IO) { fuel.delete(f, settings) }
                        editing = null
                        message = null
                        historyMessage = "Fill-up deleted." + (change.factor?.let { " Correction back to ${fmt(it, 3)}." } ?: "")
                        onChanged()
                    }
                },
            )
        }
    }
}

/** A number as typed: no trailing zeros, always a full stop for the decimal point. */
private fun plain(v: Double): String =
    java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/**
 * The fill-up form: for a new fill-up, or, with [initial], to correct a saved one. A correction keeps the
 * fill-up's time and the price that was paid, and offers [onDelete] (tapped twice, so never by accident).
 */
@Composable
private fun FillupForm(settings: AppSettings, initial: Fillup? = null, onDelete: (() -> Unit)? = null, onSave: (Fillup) -> Unit) {
    var litres by remember { mutableStateOf(initial?.litres?.let(::plain) ?: "") }
    var price by remember { mutableStateOf(initial?.price?.let(::plain) ?: fmt(settings.fuelPrice, 2)) }
    var priceNote by remember { mutableStateOf(if (initial == null) "Last price used" else "The price paid at the time") }
    var full by remember { mutableStateOf(initial?.full ?: true) }
    var odometer by remember { mutableStateOf(initial?.odometer?.let(::plain) ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    // A new fill-up fetches today's price once when the form opens.
    LaunchedEffect(Unit) {
        if (initial != null) return@LaunchedEffect
        priceNote = "Checking today's price..."
        val today = withContext(Dispatchers.IO) { PetrolPrice.fetch() }
        if (today != null) {
            price = fmt(today, 2)
            priceNote = "Today's petrol price from gari.pk. Edit if your pump differs."
        } else {
            priceNote = "Could not fetch today's price. Showing the last price used."
        }
    }

    Column(Modifier.padding(top = 12.dp)) {
        NumberField("Litres", litres, "L") { litres = it }
        NumberField("Price", price, "PKR/L") { price = it }
        Text(priceNote, style = Type.label)
        Spacer(Modifier.height(12.dp))
        Text("Filled to full", style = Type.label)
        Spacer(Modifier.height(8.dp))
        Chips(listOf(true, false), full, { if (it) "Full tank" else "Partial" }, inset = 0.dp) { full = it }
        NumberField("Odometer (optional)", odometer, "km") { odometer = it }
        Spacer(Modifier.height(10.dp))
        val l = litres.toDoubleOrNull()
        val p = price.toDoubleOrNull()
        OutlineButton(
            if (l != null && p != null) "${if (initial == null) "Save" else "Save changes"}  ${fmt(l * p, 0)} PKR" else "Enter litres and price",
            color = if (l != null && p != null) Palette.Accent else Palette.Label,
        ) {
            if (l != null && l > 0 && p != null && p > 0) {
                onSave(Fillup(timeMs = initial?.timeMs ?: System.currentTimeMillis(), litres = l, price = p, full = full, odometer = odometer.toDoubleOrNull()))
            }
        }
        if (onDelete != null) {
            Spacer(Modifier.height(8.dp))
            OutlineButton(
                if (confirmDelete) "Tap again to delete this fill-up" else "Delete this fill-up",
                color = if (confirmDelete) Palette.Warning else Palette.Label,
            ) { if (confirmDelete) onDelete() else confirmDelete = true }
        }
    }
}

/** A saved fill-up. Tapping it opens the form under it to correct or delete it; tapping again closes it. */
@Composable
private fun FillupCard(
    f: Fillup,
    open: Boolean,
    settings: AppSettings,
    onToggle: () -> Unit,
    onSave: (Fillup) -> Unit,
    onDelete: () -> Unit,
) {
    val day = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).format(Date(f.timeMs))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (open) Palette.Accent.copy(alpha = 0.5f) else Palette.Hairline, RoundedCornerShape(12.dp))
    ) {
        Column(Modifier.fillMaxWidth().pressable(onClick = onToggle).padding(16.dp)) {
            FillupSummary(f, day, open)
        }
        AnimatedVisibility(open, enter = fadeIn(Motion.fade()) + expandVertically(Motion.settle()), exit = fadeOut(Motion.fade(160)) + shrinkVertically(Motion.settle())) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                FillupForm(settings, initial = f, onDelete = onDelete, onSave = onSave)
                Spacer(Modifier.height(8.dp))
                OutlineButton("Cancel", color = Palette.Label, onClick = onToggle)
            }
        }
    }
}

@Composable
private fun FillupSummary(f: Fillup, day: String, open: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(day, style = Type.body, modifier = Modifier.weight(1f))
            Text(if (f.full) "Full" else "Partial", style = Type.label.copy(color = if (f.full) Palette.Accent else Palette.Label))
            Spacer(Modifier.width(14.dp))
            Text(if (open) "Close" else "Edit", style = Type.label.copy(color = Palette.Accent))
        }
        Row(Modifier.fillMaxWidth()) {
            Tile("Litres", fmt(f.litres, 1), "L", Modifier.weight(1f))
            Tile("Price", fmt(f.price, 0), "PKR/L", Modifier.weight(1f))
            Tile("Cost", fmt(f.cost, 0), "PKR", Modifier.weight(1f))
        }
        if (f.appLitres != null) {
            val error = (f.appLitres - f.litres) / f.litres * 100
            Text(
                "App counted ${fmt(f.appLitres, 1)} L since the previous full tank (${fmt(error, 0)} %)." +
                    (f.factorAfter?.let { "  Correction set to ${fmt(it, 3)}." } ?: ""),
                style = Type.label,
            )
        }
    }
}
