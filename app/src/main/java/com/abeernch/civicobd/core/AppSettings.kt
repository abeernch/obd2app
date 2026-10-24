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

    /** Fuel tank size in litres. 50 L for the 7th-generation Civic. */
    var tankL: Double
        get() = prefs.getFloat("tank_l", 50f).toDouble()
        set(value) = prefs.edit().putFloat("tank_l", value.toFloat()).apply()

    /** Last odometer reading the user entered, and when. Logged trips are added on top of it. */
    var odometerKm: Double?
        get() = if (prefs.contains("odometer_km")) prefs.getFloat("odometer_km", 0f).toDouble() else null
        set(value) {
            if (value == null) prefs.edit().remove("odometer_km").apply()
            else prefs.edit().putFloat("odometer_km", value.toFloat()).apply()
        }

    var odometerAtMs: Long
        get() = prefs.getLong("odometer_at_ms", 0L)
        set(value) = prefs.edit().putLong("odometer_at_ms", value).apply()

    /** Oil change interval and the odometer reading at the last change. */
    var oilIntervalKm: Double
        get() = prefs.getFloat("oil_interval_km", 5000f).toDouble()
        set(value) = prefs.edit().putFloat("oil_interval_km", value.toFloat()).apply()

    var oilChangedAtKm: Double?
        get() = if (prefs.contains("oil_changed_km")) prefs.getFloat("oil_changed_km", 0f).toDouble() else null
        set(value) {
            if (value == null) prefs.edit().remove("oil_changed_km").apply()
            else prefs.edit().putFloat("oil_changed_km", value.toFloat()).apply()
        }

    /** Drive screen behaviour. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    var showDriveOnStart: Boolean
        get() = prefs.getBoolean("drive_on_start", true)
        set(value) = prefs.edit().putBoolean("drive_on_start", value).apply()

    /** Gear ratios learned from driving, km/h per 1000 rpm, comma separated. Empty means defaults. */
    var gearRatios: String
        get() = prefs.getString("gear_ratios", "") ?: ""
        set(value) = prefs.edit().putString("gear_ratios", value).apply()

    var askedBattery: Boolean
        get() = prefs.getBoolean("asked_battery", false)
        set(value) = prefs.edit().putBoolean("asked_battery", value).apply()
}
