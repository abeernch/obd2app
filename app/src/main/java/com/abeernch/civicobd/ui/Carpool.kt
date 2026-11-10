package com.abeernch.civicobd.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.TripRow
import kotlin.math.roundToLong

/** "Rs 1,234", or "Rs −130" below zero. */
internal fun rupees(v: Double): String {
    val r = v.roundToLong()
    return if (r < 0) "Rs −" + "%,d".format(-r) else "Rs " + "%,d".format(r)
}

/** The colour for a net cost: green when the carpooling share covered all of the fuel and more. */
@Composable
internal fun netColor(trip: TripRow) = if (trip.shared > 0 && trip.netCost < 0) Lux.Green else Palette.Value

/**
 * The carpooling share of a drive, as a quiet line under its figures: "Carpooling share  Add", or once
 * entered "Carpooling share Rs 800 · 2 passengers  Edit" over "Net cost Rs −130". Tapping it opens the
 * editor in place, or over the screen when [inline] is false (a screen that does not scroll, so has no room
 * to open it in). [quiet] is the text style of the screen it sits on. [onSave] stores the total and the
 * number of passengers (zero clears it).
 */
@Composable
internal fun ShareLine(
    trip: TripRow,
    settings: AppSettings,
    modifier: Modifier = Modifier,
    quiet: TextStyle? = null,
    inline: Boolean = true,
    onSave: (Double, Int) -> Unit,
) {
    var editing by remember(trip.id) { mutableStateOf(false) }
    if (!inline && editing) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { editing = false }) {
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Palette.Surface).padding(8.dp)) {
                ShareEditor(trip, settings, Modifier, onDone = { editing = false }) { total, count ->
                    editing = false
                    onSave(total, count)
                }
            }
        }
    }
    AnimatedContent(editing && inline, transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade(150)) }, label = "share") { open ->
        if (open) {
            ShareEditor(trip, settings, modifier, onDone = { editing = false }) { total, count ->
                editing = false
                onSave(total, count)
            }
        } else {
            val has = trip.shared > 0
            val base = quiet ?: Type.body.copy(fontSize = Type.sz(15f), color = Palette.Label)
            val value = base.copy(color = if (quiet != null) Lux.Txt else Palette.Value)
            Column(
                modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .semantics(mergeDescendants = true) { contentDescription = if (has) "Edit carpooling share" else "Add carpooling share" }
                    .pressable { editing = true }
                    .padding(vertical = 8.dp, horizontal = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Carpooling share", style = base, maxLines = 1)
                    if (has) {
                        Text(
                            "  ${rupees(trip.shared)}" + (if (trip.passengers > 0) " · ${trip.passengers} passenger${if (trip.passengers == 1) "" else "s"}" else ""),
                            style = value,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Text(if (has) "Edit" else "Add", style = base.copy(color = Palette.Accent), modifier = Modifier.padding(start = 12.dp))
                }
                if (has) {
                    Row(Modifier.padding(top = 2.dp)) {
                        Text("Net cost", style = base)
                        Text("  ${rupees(trip.netCost)}", style = value.copy(color = if (trip.netCost < 0) Lux.Green else value.color))
                    }
                }
            }
        }
    }
}

/**
 * How many passengers, times each one's share. Opens with this drive's figures, or else the usual carpool
 * (the last one entered), so a regular carpool is a tap or two.
 */
@Composable
private fun ShareEditor(trip: TripRow, settings: AppSettings, modifier: Modifier, onDone: () -> Unit, onSave: (Double, Int) -> Unit) {
    val startCount = if (trip.passengers > 0) trip.passengers else settings.carpoolPassengers.coerceIn(1, 6)
    val startEach = if (trip.passengers > 0) trip.shared / trip.passengers else settings.carpoolEach
    var count by remember { mutableIntStateOf(startCount) }
    var each by remember { mutableStateOf(startEach?.let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.2f".format(java.util.Locale.US, it) } ?: "") }
    val perHead = each.toDoubleOrNull()?.takeIf { it > 0 && it <= 100_000 }
    val total = perHead?.let { it * count }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Palette.Accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text("Carpooling share", style = Type.body.copy(color = Palette.Value))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Passengers", style = Type.label.copy(fontSize = Type.body.fontSize), modifier = Modifier.weight(1f))
            StepButton("−", "One passenger fewer", enabled = count > 1) { count-- }
            Text("$count", style = Type.value.copy(fontSize = Type.sz(24f)), modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
            StepButton("+", "One passenger more", enabled = count < 6) { count++ }
        }
        NumberField("Share each", each, "PKR") { each = it }
        Text(
            if (total != null) "Carpooling share ${rupees(total)}. Net cost of the drive ${rupees(trip.cost - total)}."
            else "Enter each passenger's share.",
            style = Type.label.copy(color = if (total != null && trip.cost - total < 0) Lux.Green else Palette.Label),
        )
        Spacer(Modifier.height(10.dp))
        OutlineButton(if (total != null) "Save  ${rupees(total)}" else "Save", color = if (total != null) Palette.Accent else Palette.Label) {
            if (total != null) {
                // Remembered as the usual carpool, offered first next time.
                settings.carpoolPassengers = count
                settings.carpoolEach = perHead
                onSave(total, count)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { OutlineButton("Cancel", color = Palette.Label, onClick = onDone) }
            if (trip.shared > 0) Box(Modifier.weight(1f)) { OutlineButton("Remove", color = Palette.Warning) { onSave(0.0, 0) } }
        }
    }
}

@Composable
private fun StepButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, if (enabled) Palette.Accent else Palette.Hairline, RoundedCornerShape(20.dp))
            .semantics { contentDescription = description }
            .pressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.value.copy(fontSize = Type.sz(22f), color = if (enabled) Palette.Accent else Palette.Hairline)) }
}
