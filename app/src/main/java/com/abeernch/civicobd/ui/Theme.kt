package com.abeernch.civicobd.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.abeernch.civicobd.R

// Colour carries meaning only (spec section 7.2).
object Palette {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF0D0F10)      // panels, one step above black
    val Hairline = Color(0xFF1E2326)     // dividers
    val Value = Color(0xFFFFFFFF)        // live numbers
    val Label = Color(0xFF8A949C)        // names and units
    val Caution = Color(0xFFF2A33A)      // amber
    val Warning = Color(0xFFE5484D)      // red
    val Target = Color(0xFF4CC9E0)       // cyan: connected, setpoints
}

// B612: the open-source typeface made for Airbus cockpit displays.
val B612 = FontFamily(
    Font(R.font.b612_regular, FontWeight.Normal),
    Font(R.font.b612_bold, FontWeight.Bold),
)

// Mono variant, used only for the raw adapter log.
val B612Mono = FontFamily(Font(R.font.b612_mono, FontWeight.Normal))
