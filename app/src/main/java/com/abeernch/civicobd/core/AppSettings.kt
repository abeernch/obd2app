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
        get() = prefs.getString("accent", "Gold") ?: "Gold"
        set(value) = prefs.edit().putString("accent", value).apply()

    var face: String
        get() = prefs.getString("face", "Saira") ?: "Saira"
        set(value) = prefs.edit().putString("face", value).apply()

    /** Bumped when a redesign changes defaults that should apply once to existing installs. */
    var designVersion: Int
        get() = prefs.getInt("design_version", 1)
        set(value) = prefs.edit().putInt("design_version", value).apply()

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

    /** Route map colour scale ("Parula" or "RedYellowGreen"). */
    var routeScale: String
        get() = prefs.getString("route_scale", "Parula") ?: "Parula"
        set(value) = prefs.edit().putString("route_scale", value).apply()

    /** Metres left off the start and end of routes in exports, so home and work are not shown. 0 = off. */
    var privacyTrimM: Double
        get() = prefs.getFloat("privacy_trim_m", 200f).toDouble()
        set(value) = prefs.edit().putFloat("privacy_trim_m", value.toFloat()).apply()

    /** Street map under routes: "Esri" or "Osm". */
    var mapTiles: String
        get() = prefs.getString("map_tiles", "Esri") ?: "Esri"
        set(value) = prefs.edit().putString("map_tiles", value).apply()

    /** Which view the Drive screen opens on: 0 gauges, 1 live map. Remembered from last time. */
    var driveView: Int
        get() = prefs.getInt("drive_view", 0)
        set(value) = prefs.edit().putInt("drive_view", value).apply()

    /** Shrink to a small floating window of key numbers when leaving the app during a trip. */
    var floatingWindow: Boolean
        get() = prefs.getBoolean("floating_window", true)
        set(value) = prefs.edit().putBoolean("floating_window", value).apply()

    /** Fuel gauge on the Drive screen: a needle dial (true) or a digital bar (false). */
    var fuelGaugeAnalog: Boolean
        get() = prefs.getBoolean("fuel_gauge_analog", true)
        set(value) = prefs.edit().putBoolean("fuel_gauge_analog", value).apply()

    /** Service jobs and their intervals, as JSON. Empty until first saved; see Maintenance. */
    var serviceJson: String?
        get() = prefs.getString("service_items", null)
        set(value) = prefs.edit().putString("service_items", value).apply()

    /** Service jobs already announced as due, as "key@doneKm" entries, so each is announced once. */
    var serviceNotified: String
        get() = prefs.getString("service_notified", "") ?: ""
        set(value) = prefs.edit().putString("service_notified", value).apply()

    /** Fault codes already announced, comma separated, and whether the check-engine light was on. */
    var codesNotified: String
        get() = prefs.getString("codes_notified", "") ?: ""
        set(value) = prefs.edit().putString("codes_notified", value).apply()

    var milWasOn: Boolean
        get() = prefs.getBoolean("mil_was_on", false)
        set(value) = prefs.edit().putBoolean("mil_was_on", value).apply()

    /** Names given to repeated routes, as JSON keyed by the route's first trip id. */
    var routeNames: String
        get() = prefs.getString("route_names", "{}") ?: "{}"
        set(value) = prefs.edit().putString("route_names", value).apply()

    /** The drive the user ended by hand, so it is not picked up again as a continuing drive. */
    /** Set when the app switched Bluetooth on, so it may switch it off again after the drive. */
    var btOnByApp: Boolean
        get() = prefs.getBoolean("bt_on_by_app", false)
        set(value) = prefs.edit().putBoolean("bt_on_by_app", value).apply()

    /** Set when the user turned location on from the app's prompt, so the app offers to turn it off after the drive. */
    var locOnByApp: Boolean
        get() = prefs.getBoolean("loc_on_by_app", false)
        set(value) = prefs.edit().putBoolean("loc_on_by_app", value).apply()

    var endedTripId: Long
        get() = prefs.getLong("ended_trip_id", -1L)
        set(value) = prefs.edit().putLong("ended_trip_id", value).apply()

    var askedBattery: Boolean
        get() = prefs.getBoolean("asked_battery", false)
        set(value) = prefs.edit().putBoolean("asked_battery", value).apply()
}
