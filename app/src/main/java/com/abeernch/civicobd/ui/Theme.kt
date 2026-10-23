package com.abeernch.civicobd.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.abeernch.civicobd.R

/**
 * Black instrument panel with one papaya-orange accent, the McLaren racing colour.
 * Orange marks what is live or chosen. Yellow and red are kept for caution and warning.
 */
object Palette {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF0E0E0F)      // panels, one step above black
    val Raised = Color(0xFF17171A)       // pressed and selected surfaces
    val Hairline = Color(0xFF26262A)     // dividers and outlines
    val Value = Color(0xFFF5F5F2)        // live numbers, a warm white
    val Label = Color(0xFF8C8C92)        // names and units
    val Accent = Color(0xFFFF8000)       // papaya orange
    val AccentSoft = Color(0x33FF8000)   // orange glow behind selected items
    val Caution = Color(0xFFF5C542)      // yellow, so it never reads as the accent
    val Warning = Color(0xFFE5484D)      // red
}

// B612: the open-source typeface made for Airbus cockpit displays.
val B612 = FontFamily(
    Font(R.font.b612_regular, FontWeight.Normal),
    Font(R.font.b612_bold, FontWeight.Bold),
)

// Mono variant, used only for the raw adapter log.
val B612Mono = FontFamily(Font(R.font.b612_mono, FontWeight.Normal))
