package com.abeernch.civicobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Type scale. Tabular figures stop digits from shifting sideways as they change.
object Type {
    val label = TextStyle(fontFamily = B612, fontSize = 12.sp, color = Palette.Label, letterSpacing = 0.2.sp)
    val body = TextStyle(fontFamily = B612, fontSize = 14.sp, color = Palette.Value, fontFeatureSettings = "tnum")
    val title = TextStyle(fontFamily = B612, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Value)
    val value = TextStyle(
        fontFamily = B612, fontSize = 26.sp, fontWeight = FontWeight.Bold,
        color = Palette.Value, fontFeatureSettings = "tnum",
    )
    val hero = TextStyle(
        fontFamily = B612, fontSize = 84.sp, fontWeight = FontWeight.Bold,
        color = Palette.Value, fontFeatureSettings = "tnum", lineHeight = 88.sp,
    )
    val mono = TextStyle(fontFamily = B612Mono, fontSize = 11.sp, color = Palette.Label, lineHeight = 16.sp)
}

/** Formats a number, or "--" when there is no reading yet. */
fun fmt(value: Double?, decimals: Int = 1): String =
    if (value == null || value.isNaN() || value.isInfinite()) "--" else "%.${decimals}f".format(value)

/** Seconds as "1:05" (hours and minutes) or "4 min". */
fun duration(seconds: Double): String {
    val total = (seconds / 60).toInt()
    return if (total >= 60) "%d:%02d".format(total / 60, total % 60) else "$total min"
}

/** A named group with a hairline under its label. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Text(title, style = Type.label)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Hairline))
        Spacer(Modifier.height(4.dp))
        content()
    }
}

/** Name on the left, value and unit on the right. Used for long lists of readings. */
@Composable
fun ReadingRow(name: String, value: String, unit: String = "", color: Color = Palette.Value) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = Type.label.copy(fontSize = 13.sp), modifier = Modifier.weight(1f))
        Text(value, style = Type.body.copy(color = color), textAlign = TextAlign.End)
        if (unit.isNotEmpty()) {
            Text("  $unit", style = Type.label)
        }
    }
}

/** A big number with its name and unit underneath. */
@Composable
fun Tile(name: String, value: String, unit: String, modifier: Modifier = Modifier, color: Color = Palette.Value) {
    Column(modifier.padding(vertical = 10.dp)) {
        RollingNumber(value, Type.value.copy(color = color))
        Text(if (unit.isEmpty()) name else "$name  $unit", style = Type.label)
    }
}
