package com.abeernch.civicobd.core

/** The figures shown for one finished drive, with the recent average to compare against. */
data class TripSummary(
    val trip: TripRow,
    val averageKmL: Double?, // over the 30 days before this drive
) {
    val minutes: Double get() = (trip.endMs - trip.startMs) / 60_000.0
    val kmPerL: Double? get() = if (trip.fuelL > 0.01 && trip.distanceKm > 0.05) trip.distanceKm / trip.fuelL else null
    val avgSpeed: Double? get() = (trip.driveS + trip.idleS).let { if (it > 10) trip.distanceKm / it * 3600 else null }

    /** "+8 % on your average", "-12 % on your average", or null when there is nothing to compare. */
    val versusAverage: String?
        get() {
            val k = kmPerL ?: return null
            val a = averageKmL ?: return null
            val pct = ((k - a) / a * 100).toInt()
            return when {
                pct > 2 -> "$pct% better than your average"
                pct < -2 -> "${-pct}% below your average"
                else -> "on your average"
            }
        }

    /**
     * The notification's short line and its longer text. Numbers are formatted one at a time and
     * joined as plain strings, so a "%" inside a phrase can never be read as a format code.
     */
    fun notificationText(): Pair<String, String> {
        val t = trip
        val line = listOfNotNull(
            "%.1f km".format(t.distanceKm),
            kmPerL?.let { "%.1f km/L".format(it) },
            "Rs %,d".format(t.cost.toLong()),
            "%d min".format(minutes.toInt()),
        ).joinToString("  ·  ")
        val more = listOfNotNull(
            versusAverage?.let { v -> "Economy " + v + " (" + "%.1f".format(averageKmL) + " km/L)." },
            avgSpeed?.let { "Average %.0f km/h, top %.0f km/h.".format(it, t.maxSpeed) },
            if (t.idleS > 60) "Standing %d min.".format((t.idleS / 60).toInt()) else null,
        ).joinToString(" ")
        return line to more
    }

    companion object {
        fun of(trip: TripRow, store: TripStore): TripSummary {
            val before = store.between(trip.startMs - 30L * 86_400_000, trip.startMs - 1)
            return TripSummary(trip, Summary.of(before).kmPerL)
        }
    }
}

/** Where a drive began and ended, from its GPS track. */
data class TripEnds(val trip: TripRow, val startLat: Double, val startLon: Double, val endLat: Double, val endLon: Double)

/** Several drives between the same two places, in the same direction. */
data class RouteGroup(val key: Long, val name: String, val trips: List<TripRow>) {
    private val withEconomy get() = trips.filter { it.fuelL > 0.01 && it.distanceKm > 0.05 }
    val distanceKm: Double get() = trips.map { it.distanceKm }.sorted()[trips.size / 2]
    val avgKmL: Double? get() = withEconomy.let { w -> if (w.isEmpty()) null else w.sumOf { it.distanceKm } / w.sumOf { it.fuelL } }
    val best: TripRow? get() = withEconomy.maxByOrNull { it.distanceKm / it.fuelL }
    val worst: TripRow? get() = withEconomy.minByOrNull { it.distanceKm / it.fuelL }
    val avgMinutes: Double get() = trips.map { (it.endMs - it.startMs) / 60_000.0 }.average()
    val fastest: TripRow? get() = trips.minByOrNull { it.endMs - it.startMs }
    val avgCost: Double get() = trips.map { it.cost }.average()
}

object RouteGroups {

    /** Drives whose starts are within this distance of each other, and ends likewise, are one route. */
    private const val SAME_PLACE_M = 500.0

    /**
     * Groups drives into repeated routes. A drive joins the first route whose first drive started
     * and ended near its own start and end. Only routes driven at least twice are returned,
     * most-driven first. Names come from [names] (keyed by route), otherwise "Route 1", "Route 2".
     */
    fun build(ends: List<TripEnds>, names: Map<Long, String>): List<RouteGroup> {
        val groups = mutableListOf<MutableList<TripEnds>>()
        for (e in ends.sortedBy { it.trip.startMs }) {
            val home = groups.firstOrNull { g ->
                val f = g.first()
                Route.metres(f.startLat, f.startLon, e.startLat, e.startLon) < SAME_PLACE_M &&
                    Route.metres(f.endLat, f.endLon, e.endLat, e.endLon) < SAME_PLACE_M
            }
            if (home != null) home += e else groups += mutableListOf(e)
        }
        return groups.filter { it.size >= 2 }
            .sortedByDescending { it.size }
            .mapIndexed { i, g ->
                val key = g.first().trip.id
                RouteGroup(key, names[key] ?: "Route ${i + 1}", g.map { it.trip }.sortedByDescending { it.startMs })
            }
    }
}
