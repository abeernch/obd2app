package com.abeernch.civicobd.core

/** Odometer and service reminders, built from one odometer reading plus every logged trip since. */
data class CarStatus(
    val odometerKm: Double?,    // estimated reading now
    val oilDueInKm: Double?,    // negative when overdue
    val oilLife: Double? = null, // share of the change interval still left, 1.0 just after a change
)

object Car {
    /** The reading now: [base] plus the drives since it, the one in progress once, from its live total. */
    internal fun odometerOf(base: Double, tripsSince: List<TripRow>, liveTripKm: Double, activeTripId: Long): Double =
        base + tripsSince.filter { it.id != activeTripId }.sumOf { it.distanceKm } + liveTripKm

    /**
     * A reading taken off the dashboard now, as it is kept, with the time it holds for. While a drive is
     * being recorded it is kept as of that drive's start, less what the drive has covered so far: the drive
     * is added from its live total, and would otherwise count twice.
     */
    fun readingAt(readingNow: Double, nowMs: Long, live: TripTotals): Pair<Double, Long> =
        if (live.active && live.startMs < nowMs) (readingNow - live.distanceKm) to live.startMs else readingNow to nowMs

    /**
     * A reading kept for a moment inside a recorded drive: typed during it, by versions before [readingAt].
     * Only drives starting after the reading are added to it, so that drive was left out. Returns the reading
     * as of the drive's start, less the share of the drive already covered by then (by time), or null when
     * the reading needs no repair. Once repaired it falls at a drive's start, so it is never repaired twice.
     */
    internal fun repairedReading(reading: Double, atMs: Long, trips: List<TripRow>): Pair<Double, Long>? {
        val t = trips.firstOrNull { !it.manual && it.startMs < atMs && atMs < it.endMs } ?: return null
        val share = (atMs - t.startMs).toDouble() / (t.endMs - t.startMs)
        return (reading - t.distanceKm * share) to t.startMs
    }

    /** Repairs the reading in use, as [repairedReading] does. True when it changed. */
    fun repairReading(settings: AppSettings, store: TripStore): Boolean {
        val km = settings.odometerKm ?: return false
        val at = settings.odometerAtMs
        val (reading, from) = repairedReading(km, at, store.between(at - 24 * 3_600_000L, at)) ?: return false
        settings.odometerKm = reading
        settings.odometerAtMs = from
        return true
    }

    /**
     * [liveTripKm] is the drive in progress, [activeTripId] its saved row: that row is left out, so the
     * drive counts once, from its live total, and not again from its last save.
     */
    fun status(
        settings: AppSettings, store: TripStore, liveTripKm: Double, activeTripId: Long = -1,
        now: Long = System.currentTimeMillis(),
    ): CarStatus {
        val base = settings.odometerKm ?: return CarStatus(null, null)
        val odo = odometerOf(base, store.between(settings.odometerAtMs, now), liveTripKm, activeTripId)
        val oil = Maintenance.load(settings).firstOrNull { it.key == "oil" }?.let { Maintenance.status(it, odo, now) }
        return CarStatus(odo, oil?.dueInKm, oil?.life)
    }
}
