package com.abeernch.civicobd.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.abeernch.civicobd.core.AppSettings

data class PairedDevice(val name: String, val address: String)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    paired: List<PairedDevice>,
    onChanged: () -> Unit,
    onBattery: () -> Unit,
    onAppearance: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    var price by remember { mutableStateOf(fmt(settings.fuelPrice, 0)) }
    var factor by remember { mutableStateOf(fmt(settings.fuelFactor, 2)) }
    var adapter by remember { mutableStateOf(settings.adapterAddress) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Section("Fuel") {
            NumberField("Petrol price", price, "PKR/L") {
                price = it
                it.toDoubleOrNull()?.let { p -> settings.fuelPrice = p; onChanged() }
            }
            NumberField("Fuel correction factor", factor, "×") {
                factor = it
                it.toDoubleOrNull()?.takeIf { f -> f in 0.5..2.0 }?.let { f -> settings.fuelFactor = f }
            }
            Text(
                "After a full refill, divide the litres the pump added by the litres this app " +
                    "counted since the previous full refill. Multiply the current factor by that.",
                style = Type.label,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Section("OBD adapter") {
            if (paired.isEmpty()) {
                Text("No paired devices. Pair the adapter in Android Bluetooth settings.", style = Type.label)
            }
            paired.forEach { d ->
                val on = d.address == adapter
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .pressable {
                            adapter = d.address
                            settings.adapterAddress = d.address
                            settings.adapterName = d.name
                            settings.protocol = null
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBluetooth(if (on) Palette.Accent else Palette.Label, 18.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = Type.body.copy(color = if (on) Palette.Value else Palette.Label))
                        Text(d.address, style = Type.label)
                    }
                    if (on) Text("in use", style = Type.label.copy(color = Palette.Accent))
                }
            }
        }

        Section("Appearance") {
            // Re-read on every change so the chips always show what is saved.
            var tick by remember { mutableStateOf(0) }
            fun changed() { tick++; onAppearance() }
            key(tick) {
                Spacer(Modifier.height(10.dp))
                Text("Presets", style = Type.label)
                Spacer(Modifier.height(8.dp))
                val current = Preset.entries.firstOrNull {
                    it.mode == Appearance.mode(settings) && it.size == Appearance.size(settings) &&
                        it.accent == Appearance.accent(settings) && it.face == Appearance.face(settings)
                }
                Chips<Preset?>(Preset.entries.toList(), current, { it?.label ?: "" }, inset = 0.dp) { p ->
                    if (p != null) { Appearance.applyPreset(settings, p); changed() }
                }
                OptionRow("Mode") {
                    Chips(Mode.entries.toList(), Appearance.mode(settings), { it.label }, inset = 0.dp) {
                        settings.mode = it.name; changed()
                    }
                }
                OptionRow("Text size") {
                    Chips(TextSize.entries.toList(), Appearance.size(settings), { it.label }, inset = 0.dp) {
                        settings.textSize = it.name; changed()
                    }
                }
                OptionRow("Accent") {
                    Chips(Accent.entries.toList(), Appearance.accent(settings), { it.label }, inset = 0.dp) {
                        settings.accent = it.name; changed()
                    }
                }
                OptionRow("Typeface") {
                    Chips(Face.entries.toList(), Appearance.face(settings), { it.label }, inset = 0.dp) {
                        settings.face = it.name; changed()
                    }
                }
            }
        }

        Section("Backup") {
            Text(
                "One file with every trip, recording, fill-up and setting. Keep a copy in Drive, " +
                    "and restore it after reinstalling or on a new phone.",
                style = Type.label,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlineButton("Back up everything", onClick = onBackup)
            Spacer(Modifier.height(10.dp))
            OutlineButton("Restore from a backup", color = Palette.Label, onClick = onRestore)
        }

        Section("Demo") {
            var demo by remember { mutableStateOf(settings.demo) }
            Text(
                "Drives a simulated Civic instead of the real adapter, to try the app at home. " +
                    "Demo drives are recorded like real ones, so delete them in Logs afterwards.",
                style = Type.label,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlineButton(
                if (demo) "Stop demo drive" else "Start demo drive",
                color = if (demo) Palette.Accent else Palette.Value,
            ) {
                demo = !demo
                settings.demo = demo
            }
        }

        Section("Background") {
            Text(
                "To connect on its own every time you start the car, the app must be allowed " +
                    "to run without battery restrictions.",
                style = Type.label,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(10.dp))
                    .pressable(onClick = onBattery)
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("Allow unrestricted battery", style = Type.body)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun NumberField(name: String, value: String, unit: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Type.label.copy(fontSize = Type.body.fontSize), modifier = Modifier.weight(1f))
        BasicTextField(
            value = value,
            onValueChange = { text -> onChange(text.filter { it.isDigit() || it == '.' }) },
            singleLine = true,
            textStyle = Type.body.copy(textAlign = TextAlign.End),
            cursorBrush = SolidColor(Palette.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .width(96.dp)
                .border(1.dp, Palette.Hairline, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Text("  $unit", style = Type.label)
    }
}

@Composable
private fun OptionRow(name: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(name, style = Type.label)
        Spacer(Modifier.height(8.dp))
        content()
    }
}
