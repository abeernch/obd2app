package com.abeernch.civicobd.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abeernch.civicobd.core.Link
import com.abeernch.civicobd.core.LiveState
import com.abeernch.civicobd.core.Summary

/**
 * The screen to glance at while driving. One big number: instant economy.
 * Everything else sits in fixed places below it.
 */
@Composable
fun DriveScreen(
    live: LiveState,
    today: Summary,
    tank: com.abeernch.civicobd.core.Tank? = null,
    onHud: () -> Unit = {},
) {
    val d = live.derived
    val speed = live.values[0x0D]
    val rpm = live.values[0x0C]
    val trip = live.trip
    val running = live.link == Link.Live && (rpm ?: 0.0) > 0

    // Hero: km/L while moving, L/h while standing, "Fuel cut" while coasting in gear.
    val heroValue: String
    val heroUnit: String
    val heroColor = when {
        d.fuelCut -> Palette.Accent
        else -> Palette.Value
    }
    when {
        !running -> { heroValue = "--"; heroUnit = "km/L" }
        d.fuelCut -> { heroValue = "0.0"; heroUnit = "L/h  fuel cut" }
        d.instantKmL != null -> { heroValue = fmt(d.instantKmL); heroUnit = "km/L  instant" }
        else -> { heroValue = fmt(d.fuelLph); heroUnit = "L/h  standing" }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShiftCue(rpm, d, Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Text(
                "HUD", style = Type.label.copy(color = Palette.Accent, fontSize = Type.sz(16f)),
                modifier = Modifier.pressable(onClick = onHud).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        val heroTint by androidx.compose.animation.animateColorAsState(heroColor, Motion.fade(400), label = "hero")
        RollingNumber(heroValue, Type.hero.copy(color = heroTint))
        Text(heroUnit, style = Type.label.copy(fontSize = Type.sz(18f)))
        Spacer(Modifier.height(16.dp))
        EconomyBar(instant = d.instantKmL, average = trip.kmPerL)
        Spacer(Modifier.height(28.dp))

        Section("Fuel economy  km/L") {
            Row(Modifier.fillMaxWidth()) {
                Tile("10 s", fmt(d.avg10KmL), "", Modifier.weight(1f))
                Tile("Trip", fmt(trip.kmPerL), "", Modifier.weight(1f))
                Tile("Today", fmt(today.kmPerL), "", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(20.dp))

        Section("This trip") {
            Row(Modifier.fillMaxWidth()) {
                Tile("Distance", fmt(trip.distanceKm, 2), "km", Modifier.weight(1f))
                Tile("Fuel", fmt(trip.fuelL, 2), "L", Modifier.weight(1f))
                Tile("Cost", fmt(trip.fuelL * live.fuelPrice, 0), "PKR", Modifier.weight(1f))
            }
            if (tank?.leftL != null) {
                Row(Modifier.fillMaxWidth()) {
                    Tile("Range", fmt(tank.rangeKm, 0), "km", Modifier.weight(1f))
                    Tile("Fuel left", fmt(tank.leftL, 0), "L", Modifier.weight(1f))
                    Tile("Since fill", fmt(tank.kmPerL), "km/L", Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        Section("Engine") {
            Row(Modifier.fillMaxWidth()) {
                val gearText = when {
                    !running -> "--"
                    d.gear == 0 -> "N"
                    else -> d.gear.toString()
                }
                Tile("Gear", gearText, "", Modifier.weight(1f))
                Tile("Speed", fmt(speed, 0), "km/h", Modifier.weight(1f))
                Tile("RPM", fmt(rpm, 0), "", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                val coolant = live.values[0x05]
                Tile(
                    "Coolant", fmt(coolant, 0), "°C", Modifier.weight(1f),
                    color = if ((coolant ?: 0.0) > 105) Palette.Warning else Palette.Value,
                )
                val volts = live.voltage
                Tile(
                    "Battery", fmt(volts), "V", Modifier.weight(1f),
                    color = if (running && volts != null && volts < 13.2) Palette.Caution else Palette.Value,
                )
                Tile("Throttle", fmt(d.throttleRel, 0), "%", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A thin line from 0 to 30 km/L. The white fill is the instant value,
 * the cyan tick is the trip average, so you can see at a glance if you are above or below it.
 */
@Composable
private fun EconomyBar(instant: Double?, average: Double?) {
    val scale = 30.0
    val fill by animateFloatAsState(
        ((instant ?: 0.0) / scale).coerceIn(0.0, 1.0).toFloat(), Motion.settle(), label = "fill",
    )
    val mark by animateFloatAsState(
        ((average ?: 0.0) / scale).coerceIn(0.0, 1.0).toFloat(), Motion.glide(), label = "mark",
    )
    Column {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val y = size.height / 2
            val w = size.width
            drawLine(Palette.Hairline, Offset(0f, y), Offset(w, y), 3.dp.toPx(), StrokeCap.Round)
            if (fill > 0f) drawLine(Palette.Value, Offset(0f, y), Offset(w * fill, y), 3.dp.toPx(), StrokeCap.Round)
            if (average != null) {
                drawLine(
                    Palette.Accent, Offset(w * mark, 0f), Offset(w * mark, size.height),
                    2.dp.toPx(), StrokeCap.Round,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("0", style = Type.label)
            Text(if (average != null) "trip average ${fmt(average)}" else "", style = Type.label.copy(color = Palette.Accent))
            Text("30", style = Type.label)
        }
    }
}


/**
 * Shift light: a bar that fills as engine speed approaches the upshift point for the current
 * throttle, and turns accent when it is time to shift. "Shift down" replaces it when lugging.
 */
@Composable
fun ShiftCue(rpm: Double?, d: com.abeernch.civicobd.core.Derived, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 10.dp) {
    val target = d.shiftAtRpm
    val fill by animateFloatAsState(
        if (rpm != null && target != null) ((rpm - target * 0.6) / (target * 0.4)).toFloat().coerceIn(0f, 1f) else 0f,
        Motion.settle(), label = "shift",
    )
    val hot = d.shiftUp || d.lugging
    val color by androidx.compose.animation.animateColorAsState(
        when {
            d.lugging -> Palette.Caution
            d.shiftUp -> Palette.Accent
            else -> Palette.Value.copy(alpha = 0.55f)
        },
        Motion.fade(200), label = "shiftColor",
    )
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val r = size.height / 2
            drawRoundRect(Palette.Hairline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
            val w = if (d.lugging) size.width else size.width * fill
            if (w > 0f) {
                drawRoundRect(color, size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
            }
        }
        Text(
            when {
                d.lugging -> "Shift down"
                d.shiftUp -> "Shift up"
                target != null -> "Shift at ${fmt(target, 0)} rpm"
                else -> " "
            },
            style = Type.label.copy(color = if (hot) color else Palette.Label),
        )
    }
}
