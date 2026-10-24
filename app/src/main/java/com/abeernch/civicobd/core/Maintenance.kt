package com.abeernch.civicobd.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * One service job with its interval, by distance, by time, or both (whichever comes first),
 * and when it was last done.
 */
data class ServiceItem(
    val key: String,
    val name: String,
    val everyKm: Double?,
    val everyMonths: Int?,
    val doneKm: Double? = null,
    val doneMs: Long? = null,
)

/** How close a job is to being due. Negative values mean overdue. */
data class ServiceStatus(
    val item: ServiceItem,
    val dueInKm: Double?,
    val dueInDays: Int?,
    val life: Double?, // share of the interval left, 1 just after the job, 0 when due
) {
    val overdue: Boolean get() = (dueInKm != null && dueInKm < 0) || (dueInDays != null && dueInDays < 0)
    val soon: Boolean get() = !overdue && ((dueInKm != null && dueInKm < 500) || (dueInDays != null && dueInDays < 14))
    val known: Boolean get() = dueInKm != null || dueInDays != null
}

/** Service reminders: the list of jobs, stored with the settings, and their status. */
object Maintenance {

    private const val DAY_MS = 86_400_000L

    /** Starting list for a 2005 Civic. Every interval can be changed in the Car tab. */
    val DEFAULTS = listOf(
        ServiceItem("oil", "Engine oil and filter", 5_000.0, 6),
        ServiceItem("air", "Air filter", 20_000.0, 12),
        ServiceItem("plugs", "Spark plugs", 100_000.0, 60),
        ServiceItem("coolant", "Coolant", 40_000.0, 24),
        ServiceItem("brake", "Brake fluid", 40_000.0, 24),
        ServiceItem("gearbox", "Gearbox oil", 40_000.0, 36),
        ServiceItem("tyres", "Tyre rotation", 10_000.0, 12),
    )

    fun load(settings: AppSettings): List<ServiceItem> {
        val saved = settings.serviceJson
        if (saved.isNullOrBlank()) {
            // First use: carry over the oil-change interval and reading entered in older versions.
            return DEFAULTS.map {
                if (it.key == "oil") it.copy(everyKm = settings.oilIntervalKm, doneKm = settings.oilChangedAtKm) else it
            }
        }
        return try {
            val arr = JSONArray(saved)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ServiceItem(
                    key = o.getString("key"),
                    name = o.getString("name"),
                    everyKm = o.optDouble("everyKm").takeIf { !it.isNaN() },
                    everyMonths = if (o.has("everyMonths")) o.getInt("everyMonths") else null,
                    doneKm = o.optDouble("doneKm").takeIf { !it.isNaN() },
                    doneMs = if (o.has("doneMs")) o.getLong("doneMs") else null,
                )
            }
        } catch (e: Exception) {
            DEFAULTS
        }
    }

    fun save(settings: AppSettings, items: List<ServiceItem>) {
        val arr = JSONArray()
        items.forEach { s ->
            arr.put(JSONObject().apply {
                put("key", s.key)
                put("name", s.name)
                s.everyKm?.let { put("everyKm", it) }
                s.everyMonths?.let { put("everyMonths", it) }
                s.doneKm?.let { put("doneKm", it) }
                s.doneMs?.let { put("doneMs", it) }
            })
        }
        settings.serviceJson = arr.toString()
        // Keep the older oil settings in step, for anything that still reads them.
        items.firstOrNull { it.key == "oil" }?.let { oil ->
            oil.everyKm?.let { settings.oilIntervalKm = it }
            settings.oilChangedAtKm = oil.doneKm
        }
    }

    fun status(item: ServiceItem, odometerKm: Double?, now: Long = System.currentTimeMillis()): ServiceStatus {
        val dueKm = if (item.everyKm != null && item.doneKm != null && odometerKm != null) {
            item.doneKm + item.everyKm - odometerKm
        } else null
        val dueDays = if (item.everyMonths != null && item.doneMs != null) {
            val dueAt = item.doneMs + item.everyMonths * 30L * DAY_MS
            ((dueAt - now) / DAY_MS).toInt()
        } else null
        val lifeKm = if (dueKm != null && item.everyKm != null) dueKm / item.everyKm else null
        val lifeTime = if (dueDays != null && item.everyMonths != null) dueDays / (item.everyMonths * 30.0) else null
        val life = listOfNotNull(lifeKm, lifeTime).minOrNull()
        return ServiceStatus(item, dueKm, dueDays, life)
    }

    /** Statuses sorted with the most urgent first; jobs never logged go last. */
    fun statuses(settings: AppSettings, odometerKm: Double?): List<ServiceStatus> =
        load(settings).map { status(it, odometerKm) }
            .sortedWith(compareBy({ !it.known }, { it.life ?: 9.0 }))

    /** Plain words for when a job is due, for example "in 1,240 km" or "overdue by 2 months". */
    fun dueText(s: ServiceStatus): String {
        if (!s.known) return "not logged yet"
        val parts = mutableListOf<String>()
        s.dueInKm?.let { km ->
            parts += if (km < 0) "overdue by %,d km".format((-km).toLong()) else "in %,d km".format(km.toLong())
        }
        s.dueInDays?.let { d ->
            val text = when {
                d < 0 && -d >= 60 -> "overdue by ${-d / 30} months"
                d < 0 -> "overdue by ${-d} days"
                d >= 60 -> "in ${d / 30} months"
                else -> "in $d days"
            }
            parts += text
        }
        return parts.joinToString(" or ")
    }
}
