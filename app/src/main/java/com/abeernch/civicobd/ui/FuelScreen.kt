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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
        Row(Modifier.fillMaxWidth()) {
            Tile("Odometer", fmt(status.odometerKm, 0), "km", Modifier.weight(1f))
            val due = status.oilDueInKm
            Tile(
                if (due != null && due < 0) "Oil overdue" else "Oil change in",
                fmt(due?.let { kotlin.math.abs(it) }, 0), "km", Modifier.weight(2f),
                color = when {
                    due == null -> Palette.Value
                    due < 0 -> Palette.Warning
                    due < 500 -> Palette.Caution
                    else -> Palette.Value
                },
            )
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
            OutlineButton(if (adding) "Cancel" else "Log a fill-up", color = Palette.Accent) { adding = !adding }
            AnimatedVisibility(adding, enter = fadeIn(Motion.fade()) + expandVertically(Motion.settle()), exit = fadeOut(Motion.fade(160)) + shrinkVertically(Motion.settle())) {
                FillupForm(settings) { f ->
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { fuel.add(f, settings) }
                        adding = false
                        message = when {
                            saved.factorAfter != null ->
                                "Saved. The app counted ${fmt(saved.appLitres, 1)} L since the last full tank, " +
                                    "the pump put in ${fmt(f.litres, 1)} L. Correction is now ${fmt(saved.factorAfter, 3)}."
                            saved.full -> "Saved. The next full tank will calibrate the fuel count."
                            else -> "Saved as a partial fill."
                        }
                        onChanged()
                    }
                }
            }
            message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = Type.label.copy(color = Palette.Value))
            }
        }
        item { Text(if (fills.isEmpty()) "No fill-ups yet" else "History", style = Type.section) }
        items(fills, key = { it.id }) { f -> FillupCard(f) }
    }
}

@Composable
private fun FillupForm(settings: AppSettings, onSave: (Fillup) -> Unit) {
    var litres by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(fmt(settings.fuelPrice, 2)) }
    var priceNote by remember { mutableStateOf("Last price used") }
    var full by remember { mutableStateOf(true) }
    var odometer by remember { mutableStateOf("") }

    // Fetch today's price once when the form opens.
    LaunchedEffect(Unit) {
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
            if (l != null && p != null) "Save  ${fmt(l * p, 0)} PKR" else "Enter litres and price",
            color = if (l != null && p != null) Palette.Accent else Palette.Label,
        ) {
            if (l != null && l > 0 && p != null && p > 0) {
                onSave(Fillup(timeMs = System.currentTimeMillis(), litres = l, price = p, full = full, odometer = odometer.toDoubleOrNull()))
            }
        }
    }
}

@Composable
private fun FillupCard(f: Fillup) {
    val day = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.US).format(Date(f.timeMs))
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Palette.Hairline, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(day, style = Type.body, modifier = Modifier.weight(1f))
            Text(if (f.full) "Full" else "Partial", style = Type.label.copy(color = if (f.full) Palette.Accent else Palette.Label))
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
