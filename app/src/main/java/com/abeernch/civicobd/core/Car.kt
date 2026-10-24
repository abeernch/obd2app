package com.abeernch.civicobd.core

/** Odometer and service reminders, built from one odometer reading plus every logged trip since. */
data class CarStatus(
    val odometerKm: Double?,    // estimated reading now
    val oilDueInKm: Double?,    // negative when overdue
)

object Car {
    fun status(settings: AppSettings, store: TripStore, liveTripKm: Double, now: Long = System.currentTimeMillis()): CarStatus {
        val base = settings.odometerKm ?: return CarStatus(null, null)
        val since = store.between(settings.odometerAtMs, now).sumOf { it.distanceKm } + liveTripKm
        val odo = base + since
        val oil = settings.oilChangedAtKm?.let { it + settings.oilIntervalKm - odo }
        return CarStatus(odo, oil)
    }
}
