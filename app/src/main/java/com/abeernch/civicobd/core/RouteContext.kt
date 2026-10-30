package com.abeernch.civicobd.core

/** One drive placed among the others between the same two places. */
data class RouteContext(
    val name: String,       // the route's name: "Route 2" until it is renamed in Trips > Routes
    val nth: Int,           // this drive is the nth on the route, oldest first
    val count: Int,         // drives on the route
    val usualKmL: Double?,  // the route's economy over its other drives
)

object RouteContexts {

    /**
     * Finds the route this drive belongs to, by the same rule as Trips > Routes (starts and ends
     * within 500 m of each other). Null when the drive has no GPS track or no other drive shares its route.
     * Reads every drive's end points, so call it off the main thread.
     */
    fun of(trip: TripRow, store: TripStore, recordings: Recordings, names: Map<Long, String>): RouteContext? {
        val recs = recordings.all().mapNotNull { r -> r.tripId?.let { it to r } }.toMap()
        if (recs[trip.id] == null) return null
        val ends = store.between(0, Long.MAX_VALUE).mapNotNull { t ->
            val rec = recs[t.id] ?: return@mapNotNull null
            val e = recordings.endpoints(rec.id) ?: return@mapNotNull null
            TripEnds(t, e[0], e[1], e[2], e[3])
        }
        val group = RouteGroups.build(ends, names).firstOrNull { g -> g.trips.any { it.id == trip.id } } ?: return null
        val oldestFirst = group.trips.sortedBy { it.startMs }
        val others = group.trips.filter { it.id != trip.id && it.fuelL > 0.01 && it.distanceKm > 0.05 }
        val usual = if (others.isEmpty()) null else others.sumOf { it.distanceKm } / others.sumOf { it.fuelL }
        return RouteContext(group.name, oldestFirst.indexOfFirst { it.id == trip.id } + 1, group.trips.size, usual)
    }

    /** 1st, 2nd, 3rd, 4th, 11th, 12th, 13th, 21st, 22nd ... */
    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    /**
     * How this drive's economy compares: against the route's usual figure when there is one,
     * otherwise against the 30-day average. Returns the sentence and whether it reads as good news
     * (null when level).
     */
    fun comparison(kmPerL: Double?, routeUsual: Double?, thirtyDay: Double?): Pair<String, Boolean?>? {
        val k = kmPerL ?: return null
        val (base, where) = when {
            routeUsual != null -> routeUsual to "your usual %.1f on this route"
            thirtyDay != null -> thirtyDay to "your 30-day average of %.1f"
            else -> return null
        }
        if (base <= 0.0) return null
        val pct = ((k - base) / base * 100).toInt()
        val against = where.format(java.util.Locale.US, base)
        return when {
            pct > 2 -> "$pct% better than $against" to true
            pct < -2 -> "${-pct}% below $against" to false
            else -> "Level with $against" to null
        }
    }
}
