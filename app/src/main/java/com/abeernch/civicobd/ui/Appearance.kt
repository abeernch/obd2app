package com.abeernch.civicobd.ui

import com.abeernch.civicobd.core.AppSettings

/** Applies the saved appearance to the live theme objects. Cheap, so it can run on every change. */
object Appearance {

    fun mode(s: AppSettings) = Mode.entries.firstOrNull { it.name == s.mode } ?: Mode.Dark
    fun size(s: AppSettings) = TextSize.entries.firstOrNull { it.name == s.textSize } ?: TextSize.Standard
    fun accent(s: AppSettings) = Accent.entries.firstOrNull { it.name == s.accent } ?: Accent.Papaya
    fun face(s: AppSettings) = Face.entries.firstOrNull { it.name == s.face } ?: Face.Barlow

    /** True when the screens should be dark, given the phone's own setting. */
    fun isDark(s: AppSettings, systemDark: Boolean) = when (mode(s)) {
        Mode.Auto -> systemDark
        Mode.Dark -> true
        Mode.Light -> false
    }

    fun apply(s: AppSettings, systemDark: Boolean) {
        Palette.apply(isDark(s, systemDark), accent(s))
        Type.scale = size(s).scale
        Fonts.face = face(s)
    }

    fun applyPreset(s: AppSettings, p: Preset) {
        s.mode = p.mode.name
        s.textSize = p.size.name
        s.accent = p.accent.name
        s.face = p.face.name
    }
}
