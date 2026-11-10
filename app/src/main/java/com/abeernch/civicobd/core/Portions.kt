package com.abeernch.civicobd.core

/**
 * A drive cut at a moment, such as a fill-up made partway through it: how much of the drive falls on
 * each side. A recorded drive is cut by its speed samples, so the kilometres before and after the stop
 * are the ones actually driven; a drive without a usable recording is cut by its time.
 */
object Portions {

    /** Speed samples of a drive: times (ms) and km/h. */
    class Speeds(val t: LongArray, val v: FloatArray)

    /**
     * Kilometres from speed samples over [from, to): each step's speed times its length. A step over 30 s is
     * the engine off (no samples come then), and adds nothing.
     */
    internal fun integrate(s: Speeds, from: Long, to: Long): Double {
        var km = 0.0
        for (i in 0 until s.t.size - 1) {
            if (s.t[i + 1] - s.t[i] > 30_000) continue
            val a = maxOf(s.t[i], from)
            val b = minOf(s.t[i + 1], to)
            if (b > a) km += s.v[i] * (b - a) / 3_600_000.0
        }
        return km
    }

    /**
     * The share of [trip] between [from] and [to], 0 to 1. From its speed samples when they cover the whole
     * drive and agree with its distance (a drive merged from parts with a gap between does not), else by time.
     * A drive with no length in time (added by hand without a time) is all on the side its start falls.
     */
    fun share(trip: TripRow, speeds: Speeds?, from: Long, to: Long): Double {
        if (trip.endMs <= trip.startMs) return if (trip.startMs in from until to) 1.0 else 0.0
        val a = maxOf(from, trip.startMs)
        val b = minOf(to, trip.endMs)
        if (b <= a) return 0.0
        if (a == trip.startMs && b == trip.endMs) return 1.0
        if (speeds != null && speeds.t.size > 1) {
            val all = integrate(speeds, trip.startMs, trip.endMs)
            val covers = speeds.t.first() - trip.startMs < 120_000 && trip.endMs - speeds.t.last() < 120_000
            val agrees = all > 0.05 && all in trip.distanceKm * 0.75..trip.distanceKm * 1.25 + 0.2
            if (covers && agrees) return (integrate(speeds, a, b) / all).coerceIn(0.0, 1.0)
        }
        return (b - a).toDouble() / (trip.endMs - trip.startMs)
    }

    /** The part of [trip] between [from] and [to], its figures scaled to [share]. */
    fun part(trip: TripRow, share: Double, from: Long, to: Long): TripRow = trip.copy(
        startMs = maxOf(from, trip.startMs),
        endMs = maxOf(maxOf(from, trip.startMs), minOf(to, trip.endMs)),
        distanceKm = trip.distanceKm * share,
        fuelL = trip.fuelL * share,
        cost = trip.cost * share,
        driveS = trip.driveS * share,
        idleS = trip.idleS * share,
        shared = trip.shared * share,
    )

    /**
     * Stops in the middle of a drive, as (start, end): the engine off (no samples for two minutes or more)
     * or standing still for three minutes or more. Stops at either end of the drive are not counted.
     */
    internal fun stops(s: Speeds): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var still = -1L
        for (i in 0 until s.t.size - 1) {
            if (s.t[i + 1] - s.t[i] >= 120_000 && i > 0) out += s.t[i] to s.t[i + 1]
            if (s.v[i] < 1f) {
                if (still < 0) still = s.t[i]
            } else {
                if (still >= 0 && s.t[i] - still >= 180_000 && still > s.t.first()) out += still to s.t[i]
                still = -1
            }
        }
        // Overlapping finds (a silent gap inside a still spell) become one stop.
        return out.sortedBy { it.first }.fold(mutableListOf()) { acc, p ->
            val last = acc.lastOrNull()
            if (last != null && p.first <= last.second) acc[acc.size - 1] = last.first to maxOf(last.second, p.second) else acc += p
            acc
        }
    }

    /** A moment a fill-up may have been made at, with what it was. */
    data class Moment(val timeMs: Long, val stopMs: Long, val tripStartMs: Long, val duringDrive: Boolean)

    /**
     * Moments for a fill-up made before [beforeMs] and after [afterMs]: the stops inside drives (the pump is
     * often on the way), and the ends of drives, newest first. [trips] are newest first, with their speeds.
     */
    fun moments(trips: List<Pair<TripRow, Speeds?>>, afterMs: Long, beforeMs: Long, max: Int = 4): List<Moment> {
        val out = mutableListOf<Moment>()
        for ((trip, speeds) in trips) {
            if (trip.manual) continue
            // Just after the drive ended, unless the next drive had already begun.
            val end = trip.endMs + 30_000
            if (end in (afterMs + 1) until beforeMs) out += Moment(end, 0, trip.startMs, duringDrive = false)
            speeds?.let { s ->
                for ((a, b) in stops(s)) {
                    val mid = a + (b - a) / 2
                    if (mid in (afterMs + 1) until beforeMs) out += Moment(mid, b - a, trip.startMs, duringDrive = true)
                }
            }
        }
        return out.sortedByDescending { it.timeMs }.take(max)
    }
}
