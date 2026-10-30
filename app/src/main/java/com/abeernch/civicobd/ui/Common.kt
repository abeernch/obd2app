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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Type scale, sized to read at arm's length in a car mount, multiplied by the chosen text size.
// Every style is computed on use, so a change of size, typeface or colours applies at once.
// Tabular figures stop digits from shifting sideways as they change.
object Type {
    var scale by mutableStateOf(1f)

    /** A font size in sp, scaled by the chosen text size. Use instead of a fixed `n.sp`. */
    fun sz(n: Float) = (n * scale).sp

    val label: TextStyle get() = TextStyle(fontFamily = Sans, fontSize = sz(15f), color = Palette.Label, letterSpacing = 0.1.sp)
    val section: TextStyle get() = TextStyle(fontFamily = Sans, fontSize = sz(17.5f), fontWeight = FontWeight.Medium, color = Palette.Label, letterSpacing = 0.3.sp)
    val body: TextStyle get() = TextStyle(fontFamily = Sans, fontSize = sz(17f), color = Palette.Value, fontFeatureSettings = "tnum")
    val title: TextStyle get() = TextStyle(
        fontFamily = Sans, fontSize = sz(if (Fonts.light) 25f else 27f),
        fontWeight = if (Fonts.light) FontWeight.Medium else FontWeight.SemiBold, color = Palette.Value,
    )
    val value: TextStyle get() = TextStyle(
        fontFamily = Numeric, fontSize = sz(if (Fonts.light) 34f else 38f),
        fontWeight = if (Fonts.light) FontWeight.Medium else FontWeight.Bold,
        color = Palette.Value, fontFeatureSettings = "tnum", lineHeight = sz(if (Fonts.light) 39f else 42f),
    )
    val hero: TextStyle get() = TextStyle(
        fontFamily = Numeric, fontSize = sz(if (Fonts.light) 110f else 120f),
        fontWeight = if (Fonts.light) FontWeight.Medium else FontWeight.Bold,
        color = Palette.Value, fontFeatureSettings = "tnum", lineHeight = sz(if (Fonts.light) 110f else 118f),
    )
    val mono: TextStyle get() = TextStyle(fontFamily = Mono, fontSize = sz(12f), color = Palette.Label, lineHeight = sz(17f))
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
        Text(title, style = Type.section)
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
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = Type.label.copy(fontSize = Type.sz(16f)), modifier = Modifier.weight(1f))
        Text(value, style = Type.body.copy(color = color), textAlign = TextAlign.End)
        if (unit.isNotEmpty()) {
            Text("  $unit", style = Type.label)
        }
    }
}

/** A big number with its name and unit underneath. */
@Composable
fun Tile(name: String, value: String, unit: String, modifier: Modifier = Modifier, color: Color = Palette.Value) {
    Column(modifier.padding(vertical = 12.dp)) {
        RollingNumber(value, Type.value.copy(color = color))
        Spacer(Modifier.height(2.dp))
        Text(if (unit.isEmpty()) name else "$name  $unit", style = Type.label, maxLines = 1)
    }
}
