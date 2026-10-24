package com.abeernch.civicobd.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin

/** One GPS fix. */
data class Fix(
    val lat: Double, val lon: Double, val speedKmh: Double?, val altitudeM: Double?, val accuracyM: Double?,
    val atMs: Long = System.currentTimeMillis(),
)

/**
 * GPS during trips only, once a second. The latest fix is read by the recorder,
 * so every recorded second carries its position next to the engine data.
 * In demo mode it drives a pretend route instead of using the real GPS.
 */
class RouteTracker(private val context: Context) {

    @Volatile var latest: Fix? = null
        private set
    private var listening = false
    private val manager = context.getSystemService(LocationManager::class.java)

    private val listener = LocationListener { loc: Location ->
        latest = Fix(
            loc.latitude, loc.longitude,
            if (loc.hasSpeed()) loc.speed * 3.6 else null,
            if (loc.hasAltitude()) loc.altitude else null,
            if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
        )
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked in hasPermission()
    fun start() {
        if (listening || !hasPermission() || manager == null) return
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            listening = true
        } catch (e: Exception) {
            listening = false
        }
    }

    fun stop() {
        if (listening) manager?.removeUpdates(listener)
        listening = false
        latest = null
    }

    // ---- Demo route: a loop through a grid of streets, driven at the simulated speed ----

    private var demoLat = 33.6844
    private var demoLon = 73.0479
    private var demoHeading = 60.0
    private var demoDistance = 0.0
    private var demoTotal = 0.0

    fun demoStep(speedKmh: Double, dtS: Double) {
        val metres = speedKmh / 3.6 * dtS
        demoDistance += metres
        // Turn 90 degrees every 600 m, with a gentle curve on every other leg.
        if (demoDistance > 600) {
            demoDistance = 0.0
            demoHeading = (demoHeading + 90) % 360
        }
        val curve = if (((demoHeading / 90).toInt() % 2) == 0) 0.02 * metres else 0.0
        demoHeading += curve
        val rad = Math.toRadians(demoHeading)
        demoLat += metres * cos(rad) / 111_320.0
        demoLon += metres * sin(rad) / (111_320.0 * cos(Math.toRadians(demoLat)))
        demoTotal += metres
        val altitude = 520 + 25 * sin(demoTotal / 900.0)
        latest = Fix(demoLat, demoLon, speedKmh, altitude, 4.0)
    }
}
