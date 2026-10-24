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

class FuelLog(private val store: TripStore) {

    fun all(): List<Fillup> {
        val out = mutableListOf<Fillup>()
        store.readableDatabase.rawQuery(
            "SELECT id, time_ms, litres, price, full, odometer, app_litres, factor_after FROM fillups ORDER BY time_ms DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += Fillup(
                    id = c.getLong(0), timeMs = c.getLong(1), litres = c.getDouble(2), price = c.getDouble(3),
                    full = c.getInt(4) == 1, odometer = if (c.isNull(5)) null else c.getDouble(5),
                    appLitres = if (c.isNull(6)) null else c.getDouble(6),
                    factorAfter = if (c.isNull(7)) null else c.getDouble(7),
                )
            }
        }
        return out
    }

    fun delete(id: Long) {
        store.writableDatabase.delete("fillups", "id = ?", arrayOf(id.toString()))
    }

    /**
     * Saves a fill-up. For a full fill after an earlier full fill, compares the litres the pump put in
     * with the litres the app counted in between, and corrects the fuel factor by that ratio.
     * Returns the saved fill-up, with the comparison filled in when one was possible.
     */
    fun add(f: Fillup, settings: AppSettings): Fillup {
        var saved = f
        if (f.full) {
            val previousFull = all().firstOrNull { it.full && it.timeMs < f.timeMs }
            if (previousFull != null) {
                val counted = store.between(previousFull.timeMs, f.timeMs).sumOf { it.fuelL }
                // Every litre added after the previous full fill, partial fills included.
                val pumped = all().filter { it.timeMs > previousFull.timeMs && it.timeMs < f.timeMs }.sumOf { it.litres } + f.litres
                if (counted >= 3.0) {
                    val ratio = (pumped / counted).coerceIn(0.6, 1.6)
                    val factor = (settings.fuelFactor * ratio).coerceIn(0.5, 2.0)
                    settings.fuelFactor = factor
                    saved = f.copy(appLitres = counted, factorAfter = factor)
                } else {
                    saved = f.copy(appLitres = counted)
                }
            }
        }
        settings.fuelPrice = f.price // later trips are costed at what this fuel cost
        if (f.odometer != null) {
            settings.odometerKm = f.odometer
            settings.odometerAtMs = f.timeMs
        }
        val id = store.writableDatabase.insert("fillups", null, ContentValues().apply {
            put("time_ms", saved.timeMs)
            put("litres", saved.litres)
            put("price", saved.price)
            put("full", if (saved.full) 1 else 0)
            saved.odometer?.let { put("odometer", it) }
            saved.appLitres?.let { put("app_litres", it) }
            saved.factorAfter?.let { put("factor_after", it) }
        })
        return saved.copy(id = id)
    }

    /** Fuel left and range, from the last full fill plus everything counted since. [liveTripL] is the trip in progress. */
    fun tank(tankL: Double, liveTripL: Double, liveTripKm: Double, now: Long = System.currentTimeMillis()): Tank {
        val fills = all()
        val lastFull = fills.firstOrNull { it.full }
        val since = lastFull?.timeMs
        val trips = if (since != null) store.between(since, now) else store.between(now - 14L * 86_400_000, now)
        val added = if (since != null) fills.filter { it.timeMs > since }.sumOf { it.litres } else 0.0
        val used = trips.sumOf { it.fuelL } + liveTripL
        val km = trips.sumOf { it.distanceKm } + liveTripKm
        val kmPerL = if (used > 0.3 && km > 3) km / used else null
        val left = if (since != null) (tankL - used + added).coerceIn(0.0, tankL) else null
        return Tank(since, used, left, kmPerL, if (left != null && kmPerL != null) left * kmPerL else null)
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
