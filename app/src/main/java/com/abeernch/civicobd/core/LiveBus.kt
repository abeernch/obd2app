package com.abeernch.civicobd.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Link { BluetoothOff, NoAdapter, Searching, Connecting, EngineOff, Live, Idle }

data class Monitor(val name: String, val available: Boolean, val complete: Boolean)

data class Monitors(val mil: Boolean, val dtcCount: Int, val list: List<Monitor>)

/** The trip being recorded right now. */
data class TripTotals(
    val id: Long = -1,
    val startMs: Long = 0,
    val distanceKm: Double = 0.0,
    val fuelL: Double = 0.0,
    val driveS: Double = 0.0,
    val idleS: Double = 0.0,
    val maxSpeed: Double = 0.0,
) {
    val active: Boolean get() = id >= 0
    val kmPerL: Double? get() = if (fuelL > 0.01 && distanceKm > 0.05) distanceKm / fuelL else null
    val avgSpeed: Double? get() = (driveS + idleS).let { if (it > 10) distanceKm / it * 3600 else null }
}

/** Everything the screens show. Written by the background service, read by the UI. */
data class LiveState(
    val link: Link = Link.Searching,
    val status: String = "Starting",
    val adapter: String = "",
    val protocol: String = "",
    val supported: List<Int> = emptyList(),
    val cycleMs: Long = 0,
    val values: Map<Int, Double> = emptyMap(),
    val voltage: Double? = null,
    val monitors: Monitors? = null,
    val derived: Derived = Derived(),
    val trip: TripTotals = TripTotals(),
    val tripsVersion: Int = 0,         // bumps whenever the trip database changes
    val fuelPrice: Double = 390.0,     // PKR per litre, mirrored from settings
    val log: List<String> = emptyList(),
    val position: LivePosition? = null, // latest GPS fix during a trip
    val tripPaused: Boolean = false,     // engine off mid-drive: the drive continues if it restarts within 10 min
    val pausedUntilMs: Long = 0,         // while paused: when the drive will be closed, for the countdown
    val locationOff: Boolean = false,    // the phone's location switch is off, so routes can't be recorded
)

/** Where the car is now. Bearing is the direction of travel in degrees from north, null until it has moved. */
data class LivePosition(val lat: Double, val lon: Double, val bearing: Double?, val accuracyM: Double?)

/** One second of the trip in progress, for drawing the trail on the live map. */
data class TrailPoint(val lat: Double, val lon: Double, val kmPerL: Double?, val fuelCut: Boolean)

object LiveBus {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state

    // Kept apart from the state above, so the one-second screen updates do not copy the whole trail.
    private val _trail = MutableStateFlow<List<TrailPoint>>(emptyList())
    val trail: StateFlow<List<TrailPoint>> = _trail

    fun addTrail(p: TrailPoint) = _trail.update { it + p }
    fun clearTrail() { _trail.value = emptyList() }
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun update(change: (LiveState) -> LiveState) = _state.update(change)

    fun log(line: String) {
        val stamped = clock.format(Date()) + "  " + line
        _state.update { it.copy(log = (it.log + stamped).takeLast(300)) }
    }

    // Set from the Drive screen's "End drive now"; the service picks it up on its next check.
    @Volatile private var endRequested = false
    fun requestEndDrive() { endRequested = true }
    fun takeEndRequest(): Boolean = endRequested.also { endRequested = false }

    fun tripsChanged() = _state.update { it.copy(tripsVersion = it.tripsVersion + 1) }
}
