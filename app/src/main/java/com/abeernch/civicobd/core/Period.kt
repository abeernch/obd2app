package com.abeernch.civicobd.core

import java.util.Calendar

/** Time ranges for statistics. Weeks start on Monday. */
enum class Period(val label: String) {
    Today("Today"),
    Yesterday("Yesterday"),
    Week("This week"),
    Days7("7 days"),
    Month("This month"),
    Days30("30 days"),
    Days90("90 days"),
    Year("This year"),
    All("All time");

    /** Start (inclusive) and end (exclusive) in milliseconds. */
    fun range(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val day = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val today = day.timeInMillis
        val end = now + 1
        val oneDay = 24L * 3600 * 1000
        return when (this) {
            Today -> today to end
            Yesterday -> (today - oneDay) to today
            Week -> {
                day.firstDayOfWeek = Calendar.MONDAY
                val back = (day.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
                (today - back * oneDay) to end
            }
            Days7 -> (today - 6 * oneDay) to end
            Month -> {
                day.set(Calendar.DAY_OF_MONTH, 1)
                day.timeInMillis to end
            }
            Days30 -> (today - 29 * oneDay) to end
            Days90 -> (today - 89 * oneDay) to end
            Year -> {
                day.set(Calendar.DAY_OF_YEAR, 1)
                day.timeInMillis to end
            }
            All -> 0L to end
        }
    }

    /** Long ranges are charted by month, short ones by day. */
    val byMonth: Boolean get() = this == Days90 || this == Year || this == All
}

/** One bar of the chart: a span of time with its short and long names. */
data class Slot(val startMs: Long, val endMs: Long, val label: String, val longLabel: String)

/**
 * A period moved [offset] steps from the current one (-1 is last week, last month and so on),
 * with its exact start and end, a title for it, and the bars to chart it with.
 */
data class Window(val startMs: Long, val endMs: Long, val title: String, val slots: List<Slot>, val canStep: Boolean)

object Windows {
    private val locale = java.util.Locale.US
    private fun fmt(p: String, ms: Long) = java.text.SimpleDateFormat(p, locale).format(java.util.Date(ms))

    private fun midnight(ms: Long): Calendar = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        firstDayOfWeek = Calendar.MONDAY
    }

    private fun Calendar.plus(field: Int, n: Int): Long = (clone() as Calendar).apply { add(field, n) }.timeInMillis

    /** "26 Oct – 1 Nov 2026", or with both years when the span crosses a new year. */
    fun span(start: Long, endExclusive: Long): String {
        val last = endExclusive - 1
        val sameYear = fmt("yyyy", start) == fmt("yyyy", last)
        return if (fmt("yyyyMMdd", start) == fmt("yyyyMMdd", last)) fmt("EEE d MMM yyyy", start)
        else (if (sameYear) fmt("d MMM", start) else fmt("d MMM yyyy", start)) + " – " + fmt("d MMM yyyy", last)
    }

    fun of(period: Period, offset: Int, now: Long = System.currentTimeMillis(), firstTripMs: Long? = null): Window {
        val today = midnight(now)
        return when (period) {
            Period.Today, Period.Yesterday -> {
                val shift = offset - if (period == Period.Yesterday) 1 else 0
                val start = today.plus(Calendar.DAY_OF_MONTH, shift)
                val end = midnight(start).plus(Calendar.DAY_OF_MONTH, 1)
                // A day is charted by hour.
                val hours = (0 until 24).map { h ->
                    val a = start + h * 3_600_000L
                    Slot(a, a + 3_600_000L, if (h % 6 == 0) "%02d".format(h) else "", "%02d:00 to %02d:00".format(h, (h + 1) % 24))
                }
                Window(start, end, fmt("EEEE, d MMM yyyy", start), hours, true)
            }
            Period.Week -> {
                val back = (today.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
                val monday = midnight(today.plus(Calendar.DAY_OF_MONTH, -back + 7 * offset))
                val end = monday.plus(Calendar.DAY_OF_MONTH, 7)
                Window(monday.timeInMillis, end, span(monday.timeInMillis, end), days(monday.timeInMillis, 7, weekdays = true), true)
            }
            Period.Days7, Period.Days30, Period.Days90 -> {
                val n = when (period) { Period.Days7 -> 7; Period.Days30 -> 30; else -> 90 }
                val end = today.plus(Calendar.DAY_OF_MONTH, 1 + n * offset)
                val start = midnight(end).plus(Calendar.DAY_OF_MONTH, -n)
                val slots = if (n <= 31) days(start, n, weekdays = n == 7) else weeks(start, end)
                Window(start, end, span(start, end), slots, true)
            }
            Period.Month -> {
                val first = midnight(today.timeInMillis).apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, offset) }
                val end = first.plus(Calendar.MONTH, 1)
                val count = first.getActualMaximum(Calendar.DAY_OF_MONTH)
                Window(first.timeInMillis, end, fmt("MMMM yyyy", first.timeInMillis), days(first.timeInMillis, count, weekdays = false), true)
            }
            Period.Year -> {
                val first = midnight(today.timeInMillis).apply { set(Calendar.DAY_OF_YEAR, 1); add(Calendar.YEAR, offset) }
                val end = first.plus(Calendar.YEAR, 1)
                Window(first.timeInMillis, end, fmt("yyyy", first.timeInMillis), months(first.timeInMillis, 12), true)
            }
            Period.All -> {
                val startMonth = midnight(firstTripMs ?: now).apply { set(Calendar.DAY_OF_MONTH, 1) }
                var count = 1
                val c = startMonth.clone() as Calendar
                while (c.plus(Calendar.MONTH, 1) <= now) { c.add(Calendar.MONTH, 1); count++ }
                val slots = months(startMonth.timeInMillis, count).takeLast(36)
                Window(0L, now + 1, if (firstTripMs != null) "Since " + fmt("d MMM yyyy", firstTripMs) else "All drives", slots, false)
            }
        }
    }

    private fun days(start: Long, n: Int, weekdays: Boolean): List<Slot> {
        val c = midnight(start)
        return (0 until n).map { i ->
            val a = c.plus(Calendar.DAY_OF_MONTH, i)
            val b = c.plus(Calendar.DAY_OF_MONTH, i + 1)
            val label = when {
                weekdays -> fmt("EEE", a)
                n <= 16 || i % 5 == 0 -> fmt("d", a)
                else -> ""
            }
            Slot(a, b, label, fmt("EEE d MMM", a))
        }
    }

    private fun weeks(start: Long, end: Long): List<Slot> {
        val out = mutableListOf<Slot>()
        var a = start
        val c = midnight(start)
        var i = 0
        while (a < end) {
            val b = minOf(c.plus(Calendar.DAY_OF_MONTH, 7 * (i + 1)), end)
            out += Slot(a, b, if (i % 3 == 0) fmt("d MMM", a) else "", span(a, b))
            a = b; i++
        }
        return out
    }

    private fun months(start: Long, n: Int): List<Slot> {
        val c = midnight(start)
        return (0 until n).map { i ->
            val a = c.plus(Calendar.MONTH, i)
            val b = c.plus(Calendar.MONTH, i + 1)
            Slot(a, b, fmt("MMM", a).take(if (n > 12) 1 else 3), fmt("MMMM yyyy", a))
        }
    }
}
