package com.abeernch.civicobd.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Link { BluetoothOff, NoAdapter, Searching, Connecting, EngineOff, Live }

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
)

object LiveBus {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun update(change: (LiveState) -> LiveState) = _state.update(change)

    fun log(line: String) {
        val stamped = clock.format(Date()) + "  " + line
        _state.update { it.copy(log = (it.log + stamped).takeLast(300)) }
    }

    fun tripsChanged() = _state.update { it.copy(tripsVersion = it.tripsVersion + 1) }
}
