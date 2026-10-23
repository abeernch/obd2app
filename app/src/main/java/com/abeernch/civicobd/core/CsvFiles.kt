package com.abeernch.civicobd.core

import java.io.BufferedReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What a CSV file contained. Times are absolute, in ms. */
class ParsedLog(val samples: List<Sample>) {
    val startMs: Long = samples.minOfOrNull { it.tMs } ?: 0L
    val endMs: Long = samples.maxOfOrNull { it.tMs } ?: 0L
}

/**
 * Reads Car Scanner CSV exports.
 *
 * Vertical (CSV #1): "SECONDS";"PID";"VALUE";"UNITS";  one reading per row.
 * Horizontal (CSV #2 and #3): a time column, then one column per parameter.
 *
 * SECONDS counts from midnight. The date comes from the file name, for example
 * 2026-10-23_19-57-16.csv. Without a date in the name, today is used.
 */
object CsvImport {

    private val nameDate = Regex("""(\d{4})-(\d{2})-(\d{2})[ _T](\d{2})-(\d{2})-(\d{2})""")

    fun parse(reader: BufferedReader, fileName: String): ParsedLog {
        val lines = reader.readLines().filter { it.isNotBlank() }
        require(lines.size >= 2) { "The file is empty" }
        val delimiter = if (lines[0].count { it == ';' } >= lines[0].count { it == ',' }) ';' else ','
        val header = split(lines[0], delimiter).map { it.trim() }
        val midnight = midnightFor(fileName)

        val upper = header.map { it.uppercase() }
        return if (upper.contains("PID") && upper.contains("VALUE")) {
            vertical(lines, delimiter, upper, midnight)
        } else {
            horizontal(lines, delimiter, header, midnight)
        }
    }

    private fun vertical(lines: List<String>, d: Char, upper: List<String>, midnight: Long): ParsedLog {
        val iTime = upper.indexOfFirst { it == "SECONDS" || it == "TIME" }.coerceAtLeast(0)
        val iPid = upper.indexOf("PID")
        val iValue = upper.indexOf("VALUE")
        val iUnit = upper.indexOf("UNITS").let { if (it < 0) upper.indexOf("UNIT") else it }
        val clock = DayClock(midnight)
        val out = ArrayList<Sample>(lines.size)
        for (line in lines.drop(1)) {
            val cells = split(line, d)
            if (cells.size <= maxOf(iTime, iPid, iValue)) continue
            val seconds = cells[iTime].toDoubleOrNull() ?: continue
            val value = cells[iValue].toDoubleOrNull() ?: continue
            val unit = if (iUnit >= 0 && iUnit < cells.size) cleanUnit(cells[iUnit]) else ""
            out += Sample(clock.toMs(seconds), cells[iPid].trim(), unit, value)
        }
        require(out.isNotEmpty()) { "No readings found in the file" }
        return ParsedLog(out)
    }

    private fun horizontal(lines: List<String>, d: Char, header: List<String>, midnight: Long): ParsedLog {
        // Each header looks like "Engine RPM (rpm)" or "Engine RPM [rpm]" or just "Engine RPM".
        val columns = header.map { splitUnit(it) }
        val clock = DayClock(midnight)
        val out = ArrayList<Sample>()
        for (line in lines.drop(1)) {
            val cells = split(line, d)
            val seconds = cells.firstOrNull()?.let { parseTime(it) } ?: continue
            val t = clock.toMs(seconds)
            for (i in 1 until minOf(cells.size, columns.size)) {
                val value = cells[i].toDoubleOrNull() ?: continue
                val (name, unit) = columns[i]
                if (name.isNotEmpty()) out += Sample(t, name, unit, value)
            }
        }
        require(out.isNotEmpty()) { "No readings found in the file" }
        return ParsedLog(out)
    }

    /** Seconds since midnight, from "71850.3" or "19:57:30.305". */
    private fun parseTime(text: String): Double? {
        text.toDoubleOrNull()?.let { return it }
        val parts = text.trim().split(':')
        if (parts.size !in 2..3) return null
        val h = parts[0].toDoubleOrNull() ?: return null
        val m = parts[1].toDoubleOrNull() ?: return null
        val s = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        return h * 3600 + m * 60 + s
    }

    private fun splitUnit(header: String): Pair<String, String> {
        val m = Regex("""^(.*?)\s*[\(\[]([^\)\]]*)[\)\]]\s*$""").find(header)
        return if (m != null) m.groupValues[1].trim() to cleanUnit(m.groupValues[2]) else header.trim() to ""
    }

    private fun cleanUnit(unit: String): String = unit.trim().replace("℃", "°C")

    /** Splits one CSV line, honouring double quotes. */
    fun split(line: String, d: Char): List<String> {
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == d && !quoted -> { out += cell.toString(); cell.clear() }
                c == '\r' -> {}
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        return out
    }

    private fun midnightFor(fileName: String): Long {
        val cal = Calendar.getInstance()
        val m = nameDate.find(fileName)
        if (m != null) {
            val g = m.groupValues
            cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt())
        }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Turns seconds-since-midnight into absolute time, adding a day when a log runs past midnight. */
    private class DayClock(private val midnight: Long) {
        private var dayOffset = 0L
        private var last = -1.0
        fun toMs(seconds: Double): Long {
            if (seconds > 1e9) return (seconds * 1000).toLong() // already epoch seconds
            if (last >= 0 && seconds < last - 43_200) dayOffset += 86_400_000L
            last = seconds
            return midnight + dayOffset + (seconds * 1000).toLong()
        }
    }
}

/** Turns an imported log into a trip summary for the statistics. */
object ImportSummary {

    fun trip(log: ParsedLog, fuelPrice: Double): TripRow {
        val byName = log.samples.groupBy { it.name }
        fun last(name: String) = byName[name]?.maxByOrNull { it.tMs }?.value

        // Integrate speed for times and as a fallback for distance.
        val speed = byName["Vehicle speed"].orEmpty().sortedBy { it.tMs }
        var driveS = 0.0
        var idleS = 0.0
        var km = 0.0
        var maxSpeed = 0.0
        for (i in 1 until speed.size) {
            val dt = ((speed[i].tMs - speed[i - 1].tMs) / 1000.0).coerceAtMost(10.0)
            val v = speed[i - 1].value
            if (v >= 1) driveS += dt else idleS += dt
            km += v * dt / 3600.0
            maxSpeed = maxOf(maxSpeed, speed[i].value)
        }

        val rate = byName["Calculated instant fuel rate"].orEmpty().sortedBy { it.tMs }
        var litres = 0.0
        for (i in 1 until rate.size) {
            val dt = ((rate[i].tMs - rate[i - 1].tMs) / 1000.0).coerceAtMost(10.0)
            litres += rate[i - 1].value * dt / 3600.0
        }

        val distance = last("Distance travelled") ?: km
        val fuel = last("Fuel used") ?: litres
        val cost = last("Fuel used price") ?: (fuel * fuelPrice)
        return TripRow(
            startMs = log.startMs, endMs = log.endMs, distanceKm = distance, fuelL = fuel, cost = cost,
            driveS = driveS, idleS = idleS, maxSpeed = maxSpeed,
        )
    }
}

/** Writes recordings and trips as CSV files for sharing. */
object CsvExport {

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(ms))

    private fun q(text: String) = "\"" + text.replace("\"", "\"\"") + "\""

    /** Car Scanner CSV #1 layout, so files stay compatible both ways. */
    fun vertical(dir: File, rec: RecordingRow, series: List<Series>): File {
        val file = File(dir, stamp(rec.startMs) + ".csv")
        val midnight = Calendar.getInstance().apply {
            timeInMillis = rec.startMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val rows = series.flatMap { s -> s.t.indices.map { i -> Triple(s.t[i], s, s.v[i]) } }.sortedBy { it.first }
        file.bufferedWriter().use { w ->
            w.write("\"SECONDS\";\"PID\";\"VALUE\";\"UNITS\";\r\n")
            for ((t, s, v) in rows) {
                val seconds = (t - midnight) / 1000.0
                w.write("${q("%.3f".format(Locale.US, seconds))};${q(s.channel.name)};${q(v.toString())};${q(s.channel.unit)};\r\n")
            }
        }
        return file
    }

    /** One row per second, one column per parameter, gaps filled with the previous value. */
    fun horizontal(dir: File, rec: RecordingRow, series: List<Series>): File {
        val file = File(dir, stamp(rec.startMs) + "_table.csv")
        val start = series.minOfOrNull { s -> s.t.firstOrNull() ?: Long.MAX_VALUE } ?: rec.startMs
        val end = series.maxOfOrNull { s -> s.t.lastOrNull() ?: 0L } ?: rec.endMs
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        file.bufferedWriter().use { w ->
            val names = series.map { s ->
                val c = s.channel
                q(if (c.unit.isEmpty()) c.name else "${c.name} (${c.unit})")
            }
            w.write((listOf(q("Time")) + names).joinToString(";"))
            w.write("\r\n")
            var t = start
            while (t <= end) {
                val cells = series.map { s ->
                    val i = s.indexAt(t)
                    if (i >= 0) s.v[i].toString() else ""
                }
                w.write((listOf(q(clock.format(Date(t)))) + cells).joinToString(";"))
                w.write("\r\n")
                t += 1000
            }
        }
        return file
    }

    fun trips(dir: File, trips: List<TripRow>): File {
        val file = File(dir, "trips_" + stamp(System.currentTimeMillis()) + ".csv")
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        file.bufferedWriter().use { w ->
            w.write("Start;End;Distance km;Fuel L;Economy km/L;Cost PKR;Driving min;Standing min;Top speed km/h\r\n")
            for (t in trips.sortedBy { it.startMs }) {
                val economy = if (t.fuelL > 0.01) t.distanceKm / t.fuelL else 0.0
                w.write(
                    listOf(
                        f.format(Date(t.startMs)), f.format(Date(t.endMs)),
                        "%.2f".format(Locale.US, t.distanceKm), "%.3f".format(Locale.US, t.fuelL),
                        "%.2f".format(Locale.US, economy), "%.0f".format(Locale.US, t.cost),
                        "%.1f".format(Locale.US, t.driveS / 60), "%.1f".format(Locale.US, t.idleS / 60),
                        "%.0f".format(Locale.US, t.maxSpeed),
                    ).joinToString(";")
                )
                w.write("\r\n")
            }
        }
        return file
    }
}
