package com.abeernch.civicobd.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.abeernch.civicobd.R

/** Appearance choices. Each has two or three options, set in Settings. */
enum class Mode(val label: String) { Auto("Auto"), Dark("Dark"), Light("Light") }

enum class TextSize(val label: String, val scale: Float) {
    Standard("Standard", 1.0f), Large("Large", 1.15f), ExtraLarge("Extra large", 1.3f)
}

/** Accent colours, each with a dark-mode and a deeper light-mode shade for contrast. */
enum class Accent(val label: String, val dark: Color, val light: Color) {
    Gold("Gold", Color(0xFFF0A54E), Color(0xFFB0701A)),
    Papaya("Papaya", Color(0xFFFF8000), Color(0xFFD96500)),
    Ice("Ice blue", Color(0xFF4CC9E0), Color(0xFF0A7FA3)),
    Lime("Lime", Color(0xFFC6F432), Color(0xFF4F7D00)),
}

enum class Face(val label: String) { Saira("Saira"), Barlow("Barlow"), Titillium("Titillium") }

/** Ready-made combinations of the four choices. */
enum class Preset(val label: String, val mode: Mode, val size: TextSize, val accent: Accent, val face: Face) {
    Night("Night", Mode.Dark, TextSize.Standard, Accent.Gold, Face.Saira),
    PapayaNight("Papaya night", Mode.Dark, TextSize.Standard, Accent.Papaya, Face.Barlow),
    GrandPrix("Grand prix", Mode.Dark, TextSize.Large, Accent.Lime, Face.Titillium),
    Daylight("Daylight", Mode.Light, TextSize.Large, Accent.Ice, Face.Barlow),
}

/**
 * The colour set in use. Every value is observable state, so changing the theme
 * repaints every screen immediately, with no restart.
 * Accent marks what is live or chosen. Caution and Warning are kept for alerts.
 */
object Palette {
    var Background by mutableStateOf(Color(0xFF000000))
    var Surface by mutableStateOf(Color(0xFF0E0E0F))   // panels, one step above the background
    var Raised by mutableStateOf(Color(0xFF17171A))    // pressed and selected surfaces
    var Hairline by mutableStateOf(Color(0xFF26262A))  // dividers and outlines
    var Value by mutableStateOf(Color(0xFFF5F5F2))     // live numbers
    var Label by mutableStateOf(Color(0xFFA6A6AD))     // names and units
    var Accent by mutableStateOf(Color(0xFFFF8000))
    var AccentSoft by mutableStateOf(Color(0x33FF8000))
    var Caution by mutableStateOf(Color(0xFFF5C542))
    var Warning by mutableStateOf(Color(0xFFE5484D))
    var isDark by mutableStateOf(true)

    fun apply(dark: Boolean, accent: Accent) {
        isDark = dark
        if (dark) {
            Background = Color(0xFF000000)
            Surface = Color(0xFF0E0E0F)
            Raised = Color(0xFF17171A)
            Hairline = Color(0xFF26262A)
            Value = Color(0xFFF5F5F2)
            Label = Color(0xFFA6A6AD)
            Caution = Color(0xFFF5C542)
            Warning = Color(0xFFE5484D)
            Accent = accent.dark
        } else {
            // Warm paper white, near-black ink. Deeper accent and alert shades for contrast in sun.
            Background = Color(0xFFF6F5F1)
            Surface = Color(0xFFECEBE6)
            Raised = Color(0xFFE2E1DB)
            Hairline = Color(0xFFD5D4CE)
            Value = Color(0xFF111113)
            Label = Color(0xFF55555C)
            Caution = Color(0xFFA87800)
            Warning = Color(0xFFC62828)
            Accent = accent.light
        }
        AccentSoft = Accent.copy(alpha = if (dark) 0.2f else 0.16f)
    }
}

// Saira: a clean, slightly squared sans with a light weight for big instrument figures.
private val Saira = FontFamily(
    Font(R.font.saira_extralight, FontWeight.ExtraLight),
    Font(R.font.saira_light, FontWeight.Light),
    Font(R.font.saira_regular, FontWeight.Normal),
    Font(R.font.saira_medium, FontWeight.Medium),
    Font(R.font.saira_semibold, FontWeight.SemiBold),
    Font(R.font.saira_bold, FontWeight.Bold),
)

// Saira Semi Condensed for numbers with the Saira face: the same letterforms, narrow enough for three across.
private val SairaNumeric = FontFamily(
    Font(R.font.saira_sc_light, FontWeight.Light),
    Font(R.font.saira_sc_regular, FontWeight.Normal),
    Font(R.font.saira_sc_medium, FontWeight.Medium),
    Font(R.font.saira_sc_semibold, FontWeight.SemiBold),
    Font(R.font.saira_sc_semibold, FontWeight.Bold),
)

/** Saira, whatever face is chosen elsewhere: the Drive tab's instrument lettering. */
val Instrument: FontFamily get() = Saira

// Barlow: modelled on DIN road-sign and instrument lettering.
private val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_semibold, FontWeight.Bold),
)

// Barlow Semi Condensed for numbers: big digits that still fit three across.
private val BarlowNumeric = FontFamily(
    Font(R.font.barlow_sc_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_sc_bold, FontWeight.Bold),
)

// Titillium Web: squared, motorsport-style lettering with equal-width digits.
private val Titillium = FontFamily(
    Font(R.font.titillium_regular, FontWeight.Normal),
    Font(R.font.titillium_regular, FontWeight.Medium),
    Font(R.font.titillium_semibold, FontWeight.SemiBold),
    Font(R.font.titillium_bold, FontWeight.Bold),
)

/** The typeface in use, also observable. */
object Fonts {
    var face by mutableStateOf(Face.Saira)
    val sans: FontFamily get() = when (face) { Face.Saira -> Saira; Face.Barlow -> Barlow; Face.Titillium -> Titillium }
    val numeric: FontFamily get() = when (face) { Face.Saira -> SairaNumeric; Face.Barlow -> BarlowNumeric; Face.Titillium -> Titillium }

    /** Saira is broad and open, so it reads best a step lighter and a touch smaller than the others. */
    val light: Boolean get() = face == Face.Saira
}

val Sans: FontFamily get() = Fonts.sans
val Numeric: FontFamily get() = Fonts.numeric

// System monospace, used only for the raw adapter log.
val Mono = FontFamily.Monospace

/**
 * Colour for an instant economy reading: red when poor, amber in the middle, green when good,
 * blended between. 8 km/L or less is red, about 13 amber, 18 or more green; fuel cut is full green.
 * Brighter shades on the dark theme and deeper ones on the light theme, so both stay readable.
 * Null (no reading, or standing still) gives the normal text colour.
 */
fun economyColor(kmPerL: Double?, fuelCut: Boolean = false, dark: Boolean = Palette.isDark): androidx.compose.ui.graphics.Color {
    // Muted on the dark theme, so the colour informs without shouting.
    val red = if (dark) androidx.compose.ui.graphics.Color(0xFFE0716A) else androidx.compose.ui.graphics.Color(0xFFD12F2F)
    val amber = if (dark) androidx.compose.ui.graphics.Color(0xFFE7B65C) else androidx.compose.ui.graphics.Color(0xFFC07E00)
    val green = if (dark) androidx.compose.ui.graphics.Color(0xFF62C88A) else androidx.compose.ui.graphics.Color(0xFF1C9A4C)
    if (fuelCut) return green
    val k = kmPerL ?: return Palette.Value
    val f = ((k - 8.0) / 10.0).coerceIn(0.0, 1.0).toFloat() // 8 -> 0, 13 -> 0.5, 18 -> 1
    return if (f < 0.5f) androidx.compose.ui.graphics.lerp(red, amber, f * 2) else androidx.compose.ui.graphics.lerp(amber, green, (f - 0.5f) * 2)
}
