package com.abeernch.civicobd.core

import android.content.Context

/** Small user settings, stored on the phone. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Bluetooth address of the OBD adapter. Found automatically on first run. */
    var adapterAddress: String?
        get() = prefs.getString("adapter_address", null)
        set(value) = prefs.edit().putString("adapter_address", value).apply()

    var adapterName: String?
        get() = prefs.getString("adapter_name", null)
        set(value) = prefs.edit().putString("adapter_name", value).apply()

    /** ELM protocol number found on first connect, for example "3". Skips the slow search next time. */
    var protocol: String?
        get() = prefs.getString("protocol", null)
        set(value) = prefs.edit().putString("protocol", value).apply()

    /** Petrol price in PKR per litre. */
    var fuelPrice: Double
        get() = prefs.getFloat("fuel_price", 390f).toDouble()
        set(value) = prefs.edit().putFloat("fuel_price", value.toFloat()).apply()

    /** Multiplies the computed fuel flow. Tuned from real fill-ups. 1.0 means no correction. */
    var fuelFactor: Double
        get() = prefs.getFloat("fuel_factor", 1.0f).toDouble()
        set(value) = prefs.edit().putFloat("fuel_factor", value.toFloat()).apply()

    /** Replaces the real adapter with a simulated car. For demos and automated tests. */
    var demo: Boolean
        get() = prefs.getBoolean("demo", false)
        set(value) = prefs.edit().putBoolean("demo", value).apply()

    // Appearance, stored as option names: see ui/Theme.kt
    var mode: String
        get() = prefs.getString("mode", "Dark") ?: "Dark"
        set(value) = prefs.edit().putString("mode", value).apply()

    var textSize: String
        get() = prefs.getString("text_size", "Standard") ?: "Standard"
        set(value) = prefs.edit().putString("text_size", value).apply()

    var accent: String
        get() = prefs.getString("accent", "Papaya") ?: "Papaya"
        set(value) = prefs.edit().putString("accent", value).apply()

    var face: String
        get() = prefs.getString("face", "Barlow") ?: "Barlow"
        set(value) = prefs.edit().putString("face", value).apply()

    var askedBattery: Boolean
        get() = prefs.getBoolean("asked_battery", false)
        set(value) = prefs.edit().putBoolean("asked_battery", value).apply()
}
