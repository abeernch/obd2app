package com.abeernch.civicobd.core

/**
 * Drives added by hand: ones the app did not record (someone else took the car, or the phone was not in it).
 * Their distance is known, from the odometer or as entered; their fuel is estimated from recent economy
 * unless the litres are entered; their cost is that fuel at the price paid at the time.
 */
object HandDrives {

    /** How the distance was given. */
    enum class Distance { OdometerNow, BeforeAfter, Kilometres }

    /** The longest drive accepted by hand, km: a guard against a mistyped reading. */
    const val MAX_KM = 2_000.0

    /**
     * Kilometres the app did not record: what the odometer shows now, less what the app expects it to show
     * (the last reading plus every drive since). Null when there is no gap to add.
     */
    fun missingKm(readingNow: Double, expectedNow: Double): Double? =
        (readingNow - expectedNow).takeIf { it >= 0.1 && it <= MAX_KM }

    /** The distance between two readings, when they make sense. */
    fun between(before: Double, after: Double): Double? = (after - before).takeIf { it >= 0.1 && it <= MAX_KM }

    /** Economy to estimate fuel with: recorded drives in the 30 days before [atMs], else all recorded drives. */
    fun recentKmPerL(before30Days: List<TripRow>, allTrips: List<TripRow>): Double? =
        Summary.of(before30Days).kmPerL ?: Summary.of(allTrips).kmPerL

    /** Litres for [km] at [kmPerL]. */
    fun estimateFuel(km: Double, kmPerL: Double): Double = if (kmPerL > 0) km / kmPerL else 0.0

    /** The price per litre at [atMs]: the latest fill-up at or before it, else the price in use now. */
    fun priceAt(fills: List<Fillup>, atMs: Long, priceNow: Double): Double =
        fills.filter { it.timeMs <= atMs }.maxByOrNull { it.timeMs }?.price ?: priceNow

    /**
     * The drive to save. [litres] is what was entered, or null to use [estimatedL]; [minutes] the time
     * driving, if known (it then ends that long after it started).
     */
    fun build(
        id: Long = -1,
        startMs: Long,
        km: Double,
        minutes: Double?,
        litres: Double?,
        estimatedL: Double?,
        pricePerL: Double,
        note: String?,
        odoStart: Double?,
        odoEnd: Double?,
        shared: Double = 0.0,
        passengers: Int = 0,
    ): TripRow {
        val fuel = litres ?: estimatedL ?: 0.0
        val seconds = (minutes ?: 0.0).coerceIn(0.0, 24 * 60.0) * 60
        return TripRow(
            id = id, startMs = startMs, endMs = startMs + (seconds * 1000).toLong(),
            distanceKm = km, fuelL = fuel, cost = fuel * pricePerL,
            driveS = seconds, idleS = 0.0, maxSpeed = 0.0,
            manual = true, fuelEstimated = litres == null && estimatedL != null,
            note = note?.trim()?.takeIf { it.isNotEmpty() }, odoStart = odoStart, odoEnd = odoEnd,
            shared = shared, passengers = passengers,
        )
    }

    /**
     * The odometer reading to keep after saving, with the time it holds for, or null to leave the one in use.
     * Drives starting at or after that time are added to it, so it is always kept for a moment after the
     * drive's start: the drive is already in the reading and must not be added again.
     *
     * A reading off the dashboard now is always the newest (kept as [Car.readingAt] keeps it, while a drive
     * is being recorded). A drive's after-reading replaces the one in use only when it is at least as recent.
     */
    fun odometerToKeep(
        mode: Distance, readingNow: Double?, odoEnd: Double?, driveStartMs: Long, driveEndMs: Long,
        nowMs: Long, inUseAtMs: Long, live: TripTotals = TripTotals(),
    ): Pair<Double, Long>? = when (mode) {
        Distance.OdometerNow -> readingNow?.let { Car.readingAt(it, maxOf(nowMs, driveStartMs + 1), live) }
        Distance.BeforeAfter -> odoEnd?.let { odo ->
            val at = maxOf(driveEndMs, driveStartMs + 1)
            (odo to at).takeIf { at >= inUseAtMs }
        }
        Distance.Kilometres -> null
    }

    /**
     * Why a drive with these times cannot be saved, or null when it can: it may not end later than now, nor
     * overlap the drive being recorded, which started at [liveStartMs].
     */
    fun timeProblem(startMs: Long, minutes: Double?, nowMs: Long, liveStartMs: Long?): String? {
        val endMs = startMs + ((minutes ?: 0.0).coerceIn(0.0, 24 * 60.0) * 60_000).toLong()
        return when {
            startMs > nowMs -> "That is later than now."
            endMs > nowMs -> "With that much driving it would end later than now. Set an earlier start."
            liveStartMs != null && endMs >= liveStartMs ->
                "A drive is being recorded since " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(liveStartMs)) +
                    ". Set this one before it."
            else -> null
        }
    }
}
