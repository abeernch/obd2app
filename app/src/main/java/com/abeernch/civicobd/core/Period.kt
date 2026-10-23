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
