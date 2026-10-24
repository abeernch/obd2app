package com.abeernch.civicobd.core

/** Odometer and service reminders, built from one odometer reading plus every logged trip since. */
data class CarStatus(
    val odometerKm: Double?,    // estimated reading now
    val oilDueInKm: Double?,    // negative when overdue
    val oilLife: Double? = null, // share of the change interval still left, 1.0 just after a change
)

object Car {
    fun status(settings: AppSettings, store: TripStore, liveTripKm: Double, now: Long = System.currentTimeMillis()): CarStatus {
        val base = settings.odometerKm ?: return CarStatus(null, null)
        val since = store.between(settings.odometerAtMs, now).sumOf { it.distanceKm } + liveTripKm
        val odo = base + since
        val oil = Maintenance.load(settings).firstOrNull { it.key == "oil" }?.let { Maintenance.status(it, odo, now) }
        return CarStatus(odo, oil?.dueInKm, oil?.life)
    }
}
