package com.abeernch.civicobd.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One second of a drive, placed on the map, with the engine data recorded at that moment. */
data class RoutePoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val km: Double,              // distance from the start of the trip
    val speed: Double?,
    val kmPerL: Double?,         // instant economy; null when stopped
    val fuelRate: Double?,       // L/h
    val rpm: Double?,
    val throttle: Double?,
    val accel: Double?,          // g
    val gear: Double?,
    val altitude: Double?,
) {
    val fuelCut: Boolean get() = (fuelRate ?: 1.0) < 0.05 && (speed ?: 0.0) > 5
}

/** What colours the path. Fixed ranges are used for the "fixed" scale, so trips compare fairly. */
enum class RouteMetric(val label: String, val unit: String, val fixedLow: Double, val fixedHigh: Double, val highIsGood: Boolean) {
    Economy("Economy", "km/L", 6.0, 20.0, true),
    Speed("Speed", "km/h", 0.0, 110.0, true),
    Rpm("RPM", "rpm", 700.0, 4500.0, false),
    Throttle("Throttle", "%", 0.0, 60.0, false),
    Accel("Acceleration", "g", 0.0, 0.3, false),
    Gear("Gear", "", 1.0, 5.0, true);

    fun of(p: RoutePoint): Double? = when (this) {
        Economy -> p.kmPerL
        Speed -> p.speed
        Rpm -> p.rpm
        Throttle -> p.throttle
        Accel -> p.accel?.let { kotlin.math.abs(it) }
        Gear -> p.gear?.takeIf { it > 0 }
    }
}

enum class ColorScale(val label: String) { Parula("Parula"), RedYellowGreen("Red–yellow–green") }

enum class ScaleMode(val label: String) { Relative("Relative to this trip"), Fixed("Fixed") }

/** A place where the car stood still with the engine running. */
data class Stop(val lat: Double, val lon: Double, val seconds: Double, val index: Int)

/** Hard braking or hard acceleration. */
data class Harsh(val lat: Double, val lon: Double, val g: Double, val index: Int)

object Route {

    /** Builds route points from a recording, or returns empty if it has no GPS. */
    fun load(rec: Recordings, recId: Long): List<RoutePoint> {
        val channels = rec.channels(recId)
        fun find(vararg names: String) = channels.firstOrNull { c -> names.any { c.name.equals(it, ignoreCase = true) } }
        val latC = find("Latitude", "GPS Latitude", "Latitude (GPS)") ?: return emptyList()
        val lonC = find("Longitude", "GPS Longitude", "Longitude (GPS)") ?: return emptyList()
        val lat = rec.series(recId, latC)
        val lon = rec.series(recId, lonC)
        fun s(vararg names: String) = find(*names)?.let { rec.series(recId, it) }
        val speed = s("Vehicle speed") ?: s("Speed (GPS)")
        val kml = s("Calculated instant fuel consumption")
        val rate = s("Calculated instant fuel rate")
        val rpm = s("Engine RPM")
        val thr = s("Throttle position")
        val acc = s("Vehicle acceleration")
        val gear = s("Gear")
        val alt = s("Altitude (GPS)", "Altitude")

        fun at(x: Series?, t: Long): Double? {
            if (x == null) return null
            val i = x.indexAt(t)
            return if (i >= 0 && t - x.t[i] < 5_000) x.v[i].toDouble() else null
        }

        val out = ArrayList<RoutePoint>(lat.size)
        var km = 0.0
        var prevLat = Double.NaN
        var prevLon = Double.NaN
        for (i in 0 until lat.size) {
            val t = lat.t[i]
            val j = lon.indexAt(t)
            if (j < 0) continue
            val la = lat.v[i].toDouble()
            val lo = lon.v[j].toDouble()
            if (la == 0.0 && lo == 0.0) continue
            if (!prevLat.isNaN()) km += distanceKm(prevLat, prevLon, la, lo)
            prevLat = la; prevLon = lo
            val sp = at(speed, t)
            out += RoutePoint(
                t, la, lo, km, sp,
                at(kml, t)?.takeIf { (sp ?: 0.0) >= 3 },
                at(rate, t), at(rpm, t), at(thr, t), at(acc, t), at(gear, t), at(alt, t),
            )
        }
        return out
    }

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Low and high ends of the colour scale for this metric. */
    fun range(points: List<RoutePoint>, metric: RouteMetric, mode: ScaleMode): Pair<Double, Double> {
        if (mode == ScaleMode.Fixed) return metric.fixedLow to metric.fixedHigh
        val values = points.mapNotNull { metric.of(it) }.sorted()
        if (values.size < 5) return metric.fixedLow to metric.fixedHigh
        val lo = values[(values.size * 0.05).toInt()]
        val hi = values[(values.size * 0.95).toInt().coerceAtMost(values.size - 1)]
        return if (hi - lo < 1e-6) (lo - 1) to (hi + 1) else lo to hi
    }

    /** 0..1 position on the colour scale, where 1 is always "good" (green or yellow). */
    fun position(value: Double, range: Pair<Double, Double>, metric: RouteMetric): Float {
        val f = ((value - range.first) / (range.second - range.first)).coerceIn(0.0, 1.0)
        return (if (metric.highIsGood) f else 1 - f).toFloat()
    }

    /** ARGB colour for a 0..1 position. */
    fun color(scale: ColorScale, f: Float): Int {
        val stops = when (scale) {
            // MATLAB parula, sampled at nine points from deep blue to yellow.
            ColorScale.Parula -> listOf(
                0x3E26A8, 0x4744E3, 0x2F70F7, 0x1B97E4, 0x0BB2C3, 0x34C18F, 0x92C04F, 0xE0B832, 0xF9FB0E,
            )
            // ColorBrewer red-yellow-green.
            ColorScale.RedYellowGreen -> listOf(0xD73027, 0xF46D43, 0xFDAE61, 0xFEE08B, 0xD9EF8B, 0xA6D96A, 0x66BD63, 0x1A9850)
        }
        val x = f.coerceIn(0f, 1f) * (stops.size - 1)
        val i = x.toInt().coerceAtMost(stops.size - 2)
        val k = x - i
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = (ch(stops[i], s) + (ch(stops[i + 1], s) - ch(stops[i], s)) * k).toInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Fuel-cut segments get their own colour, outside both scales. */
    const val FUEL_CUT_COLOR = 0xFFB69CF2.toInt()

    fun stops(points: List<RoutePoint>, minSeconds: Double = 20.0): List<Stop> {
        val out = mutableListOf<Stop>()
        var start = -1
        for (i in points.indices) {
            val still = (points[i].speed ?: 0.0) < 1.0
            if (still && start < 0) start = i
            if ((!still || i == points.lastIndex) && start >= 0) {
                val s = (points[i].t - points[start].t) / 1000.0
                if (s >= minSeconds) out += Stop(points[start].lat, points[start].lon, s, start)
                start = -1
            }
        }
        return out
    }

    fun harsh(points: List<RoutePoint>, threshold: Double = 0.25): List<Harsh> {
        val out = mutableListOf<Harsh>()
        var lastIdx = -100
        for (i in 1 until points.size) {
            // Without a recorded acceleration, work it out from speed.
            val v1 = points[i].speed
            val v0 = points[i - 1].speed
            val dt = (points[i].t - points[i - 1].t) / 1000.0
            val fromSpeed = if (v1 != null && v0 != null && dt > 0) (v1 - v0) / 3.6 / dt / 9.81 else null
            val a = points[i].accel ?: fromSpeed ?: continue
            if (kotlin.math.abs(a) >= threshold && i - lastIdx > 5) {
                out += Harsh(points[i].lat, points[i].lon, a, i)
                lastIdx = i
            }
        }
        return out
    }

    /** Drops the first and last [metres] of the route, so exports do not show where the drive began or ended. */
    fun trimForPrivacy(points: List<RoutePoint>, metres: Double): List<RoutePoint> {
        if (points.isEmpty() || metres <= 0) return points
        val total = points.last().km
        val cut = metres / 1000.0
        return points.filter { it.km >= cut && it.km <= total - cut }
    }
}

/** Economy per road stretch across all drives: ~100 m cells, total km over total litres. */
data class HeatCell(val lat: Double, val lon: Double, val kmPerL: Double, val km: Double)

object Heatmap {
    fun build(routes: List<List<RoutePoint>>): List<HeatCell> {
        val cellSize = 0.001 // about 110 m north-south
        val km = HashMap<Long, Double>()
        val litres = HashMap<Long, Double>()
        val sumLat = HashMap<Long, Double>()
        val sumLon = HashMap<Long, Double>()
        val count = HashMap<Long, Int>()
        for (route in routes) {
            for (i in 1 until route.size) {
                val p = route[i]
                val prev = route[i - 1]
                val dt = (p.t - prev.t) / 1000.0
                if (dt <= 0 || dt > 5) continue
                val speed = p.speed ?: continue
                if (speed < 5) continue
                val rate = p.fuelRate ?: continue
                val key = (Math.floor(p.lat / cellSize).toLong() shl 32) xor (Math.floor(p.lon / cellSize).toLong() and 0xFFFFFFFFL)
                km[key] = (km[key] ?: 0.0) + speed * dt / 3600
                litres[key] = (litres[key] ?: 0.0) + rate * dt / 3600
                sumLat[key] = (sumLat[key] ?: 0.0) + p.lat
                sumLon[key] = (sumLon[key] ?: 0.0) + p.lon
                count[key] = (count[key] ?: 0) + 1
            }
        }
        return km.keys.mapNotNull { k ->
            val l = litres[k] ?: return@mapNotNull null
            val d = km[k] ?: return@mapNotNull null
            if (l < 1e-4 || d < 0.02) return@mapNotNull null
            val n = count[k] ?: 1
            HeatCell(sumLat[k]!! / n, sumLon[k]!! / n, (d / l).coerceAtMost(60.0), d)
        }
    }
}

/** GPX (for any map app) and KML (Google Earth, coloured by economy) exports. */
object RouteExport {
    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(t))

    fun gpx(dir: File, name: String, points: List<RoutePoint>): File {
        val file = File(dir, "$name.gpx")
        file.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.write("<gpx version=\"1.1\" creator=\"Civic OBD\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            w.write("<trk><name>$name</name><trkseg>\n")
            for (p in points) {
                w.write("<trkpt lat=\"%.6f\" lon=\"%.6f\">".format(Locale.US, p.lat, p.lon))
                p.altitude?.let { w.write("<ele>%.1f</ele>".format(Locale.US, it)) }
                w.write("<time>${iso(p.t)}</time>")
                w.write("<desc>speed %s km/h, economy %s km/L</desc>".format(fmt(p.speed, 0), fmt(p.kmPerL, 1)))
                w.write("</trkpt>\n")
            }
            w.write("</trkseg></trk>\n</gpx>\n")
        }
        return file
    }

    fun kml(dir: File, name: String, points: List<RoutePoint>, scale: ColorScale, range: Pair<Double, Double>): File {
        val file = File(dir, "$name.kml")
        // KML colours are aabbggrr.
        fun kmlColor(argb: Int) = "ff%02x%02x%02x".format(argb and 0xFF, (argb shr 8) and 0xFF, (argb shr 16) and 0xFF)
        val buckets = 12
        file.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>$name</name>\n")
            for (b in 0..buckets) {
                val c = Route.color(scale, b / buckets.toFloat())
                w.write("<Style id=\"s$b\"><LineStyle><color>${kmlColor(c)}</color><width>6</width></LineStyle></Style>\n")
            }
            w.write("<Style id=\"cut\"><LineStyle><color>${kmlColor(Route.FUEL_CUT_COLOR)}</color><width>6</width></LineStyle></Style>\n")
            var i = 1
            while (i < points.size) {
                val p = points[i]
                val style = if (p.fuelCut) "cut" else {
                    val v = RouteMetric.Economy.of(p)
                    if (v == null) "s0" else "s${(Route.position(v, range, RouteMetric.Economy) * buckets).toInt()}"
                }
                w.write("<Placemark><styleUrl>#$style</styleUrl><LineString><coordinates>")
                w.write("%.6f,%.6f %.6f,%.6f".format(Locale.US, points[i - 1].lon, points[i - 1].lat, p.lon, p.lat))
                w.write("</coordinates></LineString></Placemark>\n")
                i++
            }
            w.write("</Document></kml>\n")
        }
        return file
    }

    private fun fmt(v: Double?, d: Int) = if (v == null) "--" else "%.${d}f".format(Locale.US, v)
}
