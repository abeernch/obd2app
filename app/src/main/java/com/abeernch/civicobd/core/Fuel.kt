package com.abeernch.civicobd.core

import android.content.ContentValues
import java.net.HttpURLConnection
import java.net.URL

/** One visit to the pump. */
data class Fillup(
    val id: Long = -1,
    val timeMs: Long,
    val litres: Double,
    val price: Double,          // PKR per litre
    val full: Boolean,          // filled to the brim, so it can calibrate
    val odometer: Double?,      // km on the car's odometer, if entered
    val appLitres: Double? = null,   // what the app had counted since the previous full fill
    val factorAfter: Double? = null, // fuel correction factor after this fill
    val factorBefore: Double? = null, // the factor this fill started from, when it set one (kept so it can be undone)
) {
    val cost: Double get() = litres * price
}

/** Tank state worked out from fill-ups and trips. */
data class Tank(
    val sinceFullMs: Long?,        // time of the last full fill, or null if none logged yet
    val usedL: Double,             // fuel counted since then
    val leftL: Double?,            // tank size minus used
    val kmPerL: Double?,           // average since the last full fill (or recent trips)
    val rangeKm: Double?,          // distance to empty
)

/** Fuel the app counted over a stretch of time, and whether a drive added by hand (its fuel estimated) is in it. */
internal data class Counted(val litres: Double, val handAdded: Boolean = false)

/** A full tank compared with the previous full tank: litres the app counted in between, and litres pumped. */
internal data class Comparison(val counted: Double, val pumped: Double, val handAdded: Boolean = false) {
    /**
     * Whether it can correct the fuel count: not with too little counted in between, and not over a stretch
     * with a drive added by hand, whose estimated fuel would be blamed on the app's own counting.
     */
    val calibrates: Boolean get() = counted >= 3.0 && !handAdded

    /** The correction after this tank, starting from [before]. */
    fun factorFrom(before: Double): Double = (before * (pumped / counted).coerceIn(0.6, 1.6)).coerceIn(0.5, 2.0)
}

/**
 * The comparison [f] makes, if it is a full tank with a full tank before it. [others] are the other
 * fill-ups, newest first; [countedBetween] is the fuel the app counted between two times.
 */
internal fun compare(f: Fillup, others: List<Fillup>, countedBetween: (Long, Long) -> Counted): Comparison? {
    if (!f.full) return null
    val previousFull = others.firstOrNull { it.full && it.timeMs < f.timeMs } ?: return null
    // Every litre added after the previous full fill, partial fills included.
    val pumped = others.filter { it.timeMs > previousFull.timeMs && it.timeMs < f.timeMs }.sumOf { it.litres } + f.litres
    val counted = countedBetween(previousFull.timeMs, f.timeMs)
    return Comparison(counted.litres, pumped, counted.handAdded)
}

/**
 * The tank from [fills] (newest first) and [trips], the drives since the last full fill-up (or of the
 * last two weeks when there is none). See [FuelLog.tank].
 */
internal fun tankOf(fills: List<Fillup>, trips: List<TripRow>, tankL: Double, liveTripL: Double, liveTripKm: Double, activeTripId: Long): Tank {
    val since = fills.firstOrNull { it.full }?.timeMs
    val counted = trips.filter { it.id != activeTripId && (since == null || it.startMs >= since) }
    val added = if (since != null) fills.filter { it.timeMs > since }.sumOf { it.litres } else 0.0
    val used = counted.sumOf { it.fuelL } + liveTripL
    val recorded = counted.filter { !it.manual }
    val km = recorded.sumOf { it.distanceKm } + liveTripKm
    val measuredL = recorded.sumOf { it.fuelL } + liveTripL
    val kmPerL = if (measuredL > 0.3 && km > 3) km / measuredL else null
    val left = if (since != null) (tankL - used + added).coerceIn(0.0, tankL) else null
    return Tank(since, used, left, kmPerL, if (left != null && kmPerL != null) left * kmPerL else null)
}

/** One full tank to the next: the distance it covered and the economy the pump gives for it. */
data class TankStretch(val km: Double, val pumpedL: Double, val handAdded: Boolean) {
    /** Distance over the litres that refilled it: the most direct measure of economy there is. */
    val kmPerL: Double? get() = if (pumpedL > 0.5 && km > 1.0) km / pumpedL else null
}

/**
 * The tank each full fill-up closed, keyed by its id: from the previous full fill-up to this one.
 * [distanceBetween] gives the distance driven between two times, drives added by hand included (they
 * burned fuel the pump put back), and whether any is in it.
 */
internal fun stretches(fills: List<Fillup>, distanceBetween: (Long, Long) -> Pair<Double, Boolean>): Map<Long, TankStretch> {
    val out = mutableMapOf<Long, TankStretch>()
    for (f in fills) {
        if (!f.full) continue
        val previousFull = fills.firstOrNull { it.full && it.timeMs < f.timeMs } ?: continue
        val pumped = fills.filter { it.timeMs > previousFull.timeMs && it.timeMs < f.timeMs }.sumOf { it.litres } + f.litres
        val (km, handAdded) = distanceBetween(previousFull.timeMs, f.timeMs)
        out[f.id] = TankStretch(km, pumped, handAdded)
    }
    return out
}

/** The correction [f] started from: kept since this build, or worked back from its comparison for older records. */
internal fun factorBefore(f: Fillup, others: List<Fillup>, countedBetween: (Long, Long) -> Counted): Double? {
    f.factorBefore?.let { return it }
    val after = f.factorAfter ?: return null
    val c = compare(f, others, countedBetween)?.takeIf { it.calibrates } ?: return null
    return (after / (c.pumped / c.counted).coerceIn(0.6, 1.6)).coerceIn(0.5, 2.0)
}

/** What changing or deleting a fill-up does: the record to keep (null when deleted), and the settings to change. */
data class FillupChange(
    val saved: Fillup?,
    val factor: Double? = null,     // new fuel correction, if it changes
    val price: Double? = null,      // new price for costing drives, if it changes
    val odometerKm: Double? = null, // new odometer reading, if it changes (taken at the fill-up's time)
)

/**
 * Works out a change to fill-up [old]: [edited] holds the corrected figures, or is null to delete it.
 * [others] are the other fill-ups, newest first. [factorNow] and [priceNow] are the settings in use,
 * [odometerAtMs] when the odometer in use was taken.
 *
 * The fuel correction changes only through the newest full tank, and only while the correction in use
 * is still the one it set: its comparison is redone from the correction it started from, or undone when
 * it is deleted (or no longer a full tank). An older full tank's comparison stays as it happened, because
 * later full tanks have corrected the count since; a correction typed in by hand is never overwritten.
 */
internal fun changeFillup(
    old: Fillup,
    edited: Fillup?,
    others: List<Fillup>,
    factorNow: Double,
    priceNow: Double,
    odometerAtMs: Long,
    countedBetween: (Long, Long) -> Counted,
): FillupChange {
    val newestFull = others.none { it.full && it.timeMs > old.timeMs }
    val newest = others.none { it.timeMs > old.timeMs }
    val setFactorInUse = old.factorAfter != null && kotlin.math.abs(factorNow - old.factorAfter) < 5e-4
    val before = if (setFactorInUse) factorBefore(old, others, countedBetween) else null

    if (edited == null) {
        return FillupChange(
            saved = null,
            factor = if (newestFull && setFactorInUse) before else null,
            // The newest fill-up set the price in use: the one before it gives the price back.
            price = if (newest && kotlin.math.abs(priceNow - old.price) < 0.01) others.firstOrNull()?.price else null,
        )
    }

    // The time may be corrected too (the form keeps it between the fill-ups either side).
    val fill = edited.copy(id = old.id)
    val c = compare(fill, others, countedBetween)
    var saved = fill.copy(appLitres = c?.counted, factorBefore = null, factorAfter = null)
    var factor: Double? = null
    if (newestFull && (setFactorInUse || old.factorAfter == null)) {
        val start = before ?: factorNow
        if (c != null && c.calibrates) {
            val f = c.factorFrom(start)
            saved = saved.copy(factorBefore = start, factorAfter = f)
            if (kotlin.math.abs(f - factorNow) >= 5e-4) factor = f
        } else if (setFactorInUse && before != null) {
            factor = before // no longer a comparison: the correction goes back
        }
    } else if (old.factorAfter != null && c != null) {
        // An older full tank keeps the correction it set at the time.
        saved = saved.copy(factorBefore = old.factorBefore, factorAfter = old.factorAfter)
    }
    return FillupChange(
        saved = saved,
        factor = factor,
        price = if (newest && kotlin.math.abs(priceNow - old.price) < 0.01 && fill.price != old.price) fill.price else null,
        // A corrected reading replaces the one in use when it is at least as recent.
        odometerKm = if (fill.odometer != null && fill.odometer != old.odometer && odometerAtMs <= old.timeMs) fill.odometer else null,
    )
}

class FuelLog(private val store: TripStore) {
    private val recordings = Recordings(store)

    /** A drive's speed samples, from its recording, to cut it at a fill-up made partway through it. */
    private fun speedsOf(trip: TripRow): Portions.Speeds? {
        if (trip.manual) return null
        val rec = recordings.forTrip(trip.id) ?: return null
        val ch = recordings.channels(rec.id).firstOrNull { it.name.equals("Vehicle speed", ignoreCase = true) } ?: return null
        val s = recordings.series(rec.id, ch)
        return Portions.Speeds(s.t, s.v)
    }

    /**
     * The drives between two times. One that a fill-up falls in the middle of counts only its part on
     * this side: the kilometres and fuel after the pump belong to the new tank.
     */
    fun tripsIn(fromMs: Long, toMs: Long): List<TripRow> =
        store.between(fromMs - 2 * 86_400_000L, toMs)
            .filter { it.startMs >= fromMs || it.endMs > fromMs }
            .mapNotNull { t ->
                if (t.startMs >= fromMs && t.endMs <= toMs) t
                else Portions.share(t, speedsOf(t), fromMs, toMs).takeIf { it > 0.0 }?.let { Portions.part(t, it, fromMs, toMs) }
            }

    /**
     * A reading taken at [atMs] (at the pump), as it is kept: for a moment inside a drive, as of that
     * drive's start, less the part of it driven by then, because drives are added to the odometer by
     * their start. [live] is the drive being recorded, counted from its live total.
     */
    internal fun readingAt(odometer: Double, atMs: Long, live: TripTotals): Pair<Double, Long> {
        if (live.active && live.startMs < atMs) {
            val row = store.byId(live.id)
            val before = row?.let { it.distanceKm * Portions.share(it, speedsOf(it), it.startMs, atMs) } ?: live.distanceKm
            return (odometer - before) to live.startMs
        }
        val inside = store.between(atMs - 2 * 86_400_000L, atMs).firstOrNull { !it.manual && it.startMs < atMs && it.endMs > atMs }
            ?: return odometer to atMs
        return (odometer - inside.distanceKm * Portions.share(inside, speedsOf(inside), inside.startMs, atMs)) to inside.startMs
    }

    /**
     * Moments a fill-up between [afterMs] and [beforeMs] may have been made at: stops inside the drives of
     * the 36 hours before, and the ends of those drives, newest first.
     */
    fun moments(afterMs: Long, beforeMs: Long): List<Portions.Moment> {
        val trips = store.between(maxOf(afterMs, beforeMs - 36 * 3_600_000L) - 86_400_000L, beforeMs)
            .filter { !it.manual }
            .map { it to speedsOf(it) }
        return Portions.moments(trips, afterMs, beforeMs)
    }

    fun all(): List<Fillup> {
        val out = mutableListOf<Fillup>()
        store.readableDatabase.rawQuery(
            "SELECT id, time_ms, litres, price, full, odometer, app_litres, factor_after, factor_before FROM fillups ORDER BY time_ms DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += Fillup(
                    id = c.getLong(0), timeMs = c.getLong(1), litres = c.getDouble(2), price = c.getDouble(3),
                    full = c.getInt(4) == 1, odometer = if (c.isNull(5)) null else c.getDouble(5),
                    appLitres = if (c.isNull(6)) null else c.getDouble(6),
                    factorAfter = if (c.isNull(7)) null else c.getDouble(7),
                    factorBefore = if (c.isNull(8)) null else c.getDouble(8),
                )
            }
        }
        return out
    }

    private fun countedBetween(fromMs: Long, toMs: Long): Counted =
        tripsIn(fromMs, toMs).let { trips -> Counted(trips.sumOf { it.fuelL }, trips.any { it.manual }) }

    /** The tank each full fill-up closed: distance and economy by the pump. See [stretches]. */
    fun tankStretches(fills: List<Fillup> = all()): Map<Long, TankStretch> = stretches(fills) { from, to ->
        tripsIn(from, to).let { trips -> trips.sumOf { it.distanceKm } to trips.any { it.manual } }
    }

    private fun values(f: Fillup) = ContentValues().apply {
        put("time_ms", f.timeMs)
        put("litres", f.litres)
        put("price", f.price)
        put("full", if (f.full) 1 else 0)
        if (f.odometer != null) put("odometer", f.odometer) else putNull("odometer")
        if (f.appLitres != null) put("app_litres", f.appLitres) else putNull("app_litres")
        if (f.factorAfter != null) put("factor_after", f.factorAfter) else putNull("factor_after")
        if (f.factorBefore != null) put("factor_before", f.factorBefore) else putNull("factor_before")
    }

    /**
     * Saves a fill-up. For a full fill after an earlier full fill, compares the litres the pump put in
     * with the litres the app counted in between, and corrects the fuel factor by that ratio.
     * Returns the saved fill-up, with the comparison filled in when one was possible.
     */
    fun add(f: Fillup, settings: AppSettings, live: TripTotals = TripTotals()): Fillup {
        var saved = f
        compare(f, all(), ::countedBetween)?.let { c ->
            saved = if (c.calibrates) {
                val before = settings.fuelFactor
                val factor = c.factorFrom(before)
                settings.fuelFactor = factor
                f.copy(appLitres = c.counted, factorBefore = before, factorAfter = factor)
            } else {
                f.copy(appLitres = c.counted)
            }
        }
        settings.fuelPrice = f.price // later trips are costed at what this fuel cost
        // The newest reading, kept for its moment (see readingAt).
        if (f.odometer != null && f.timeMs >= settings.odometerAtMs) {
            val (reading, at) = readingAt(f.odometer, f.timeMs, live)
            settings.odometerKm = reading
            settings.odometerAtMs = at
        }
        val id = store.writableDatabase.insert("fillups", null, values(saved))
        return saved.copy(id = id)
    }

    /** Corrects a saved fill-up (litres, price, full or partial, odometer). Returns the record as saved. */
    fun update(old: Fillup, edited: Fillup, settings: AppSettings, live: TripTotals = TripTotals()): Fillup {
        val change = changeFillup(
            old, edited, all().filter { it.id != old.id }, settings.fuelFactor, settings.fuelPrice,
            settings.odometerAtMs, ::countedBetween,
        )
        val saved = change.saved ?: return old
        store.writableDatabase.update("fillups", values(saved), "id = ?", arrayOf(old.id.toString()))
        apply(change, settings, saved.timeMs, live)
        return saved
    }

    /** Deletes a fill-up, undoing what it set where nothing has changed it since. Returns the correction afterwards. */
    fun delete(f: Fillup, settings: AppSettings): FillupChange {
        val change = changeFillup(
            f, null, all().filter { it.id != f.id }, settings.fuelFactor, settings.fuelPrice,
            settings.odometerAtMs, ::countedBetween,
        )
        store.writableDatabase.delete("fillups", "id = ?", arrayOf(f.id.toString()))
        apply(change, settings, f.timeMs, TripTotals())
        return change
    }

    private fun apply(change: FillupChange, settings: AppSettings, atMs: Long, live: TripTotals) {
        change.factor?.let { settings.fuelFactor = it }
        change.price?.let { settings.fuelPrice = it }
        change.odometerKm?.let {
            val (reading, at) = readingAt(it, atMs, live)
            settings.odometerKm = reading
            settings.odometerAtMs = at
        }
    }

    /**
     * Fuel left and range, from the last full fill plus everything counted since. [liveTripL] and
     * [liveTripKm] are the drive in progress, [activeTripId] its saved row: that row is left out, so the
     * drive counts once, from its live totals. Drives added by hand count towards fuel used (their fuel
     * is an estimate, but it did leave the tank); economy, and so range, comes from recorded drives only.
     */
    fun tank(tankL: Double, liveTripL: Double, liveTripKm: Double, activeTripId: Long = -1, now: Long = System.currentTimeMillis()): Tank {
        val fills = all()
        val since = fills.firstOrNull { it.full }?.timeMs
        val trips = if (since != null) tripsIn(since, now) else store.between(now - 14L * 86_400_000, now)
        // Filled up partway through the drive in progress: only its part since then is in this tank.
        var liveL = liveTripL
        var liveKm = liveTripKm
        if (since != null && activeTripId >= 0) {
            store.byId(activeTripId)?.takeIf { it.startMs < since }?.let { row ->
                val before = Portions.share(row, speedsOf(row), row.startMs, since)
                liveL = (liveTripL - row.fuelL * before).coerceAtLeast(0.0)
                liveKm = (liveTripKm - row.distanceKm * before).coerceAtLeast(0.0)
            }
        }
        return tankOf(fills, trips, tankL, liveL, liveKm, activeTripId)
    }
}

/**
 * Today's petrol (Super) price, read from gari.pk's price page.
 * Pakistan has no official price API, so this reads a public web page and checks the result is sane.
 * Returns null when offline or when the page changes its layout; the app then keeps the last price.
 */
object PetrolPrice {
    private const val SOURCE = "https://www.gari.pk/petrol-prices-in-pakistan/"

    fun fetch(): Double? = try {
        val conn = URL(SOURCE).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) CivicOBD")
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        parse(html)
    } catch (e: Exception) {
        null
    }

    /** First price after "Petrol Super" in the page text, if it is a plausible PKR price. */
    fun parse(html: String): Double? {
        val text = html.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ")
        val m = Regex("""Petrol Super[^0-9]{0,200}?(\d{3}(?:\.\d{1,2})?)""").find(text) ?: return null
        return m.groupValues[1].toDoubleOrNull()?.takeIf { it in 150.0..900.0 }
    }
}
