package com.abeernch.civicobd

import com.abeernch.civicobd.core.Channel
import com.abeernch.civicobd.core.CsvExport
import com.abeernch.civicobd.core.CsvImport
import com.abeernch.civicobd.core.ImportSummary
import com.abeernch.civicobd.core.MetricEngine
import com.abeernch.civicobd.core.RecordingRow
import com.abeernch.civicobd.core.Series
import com.abeernch.civicobd.obd.ElmSession
import com.abeernch.civicobd.obd.Pids
import com.abeernch.civicobd.obd.SimTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

/** Plain JVM tests for the parts that do not need a phone. Run on every build. */
class CoreTest {

    // First lines of the real Car Scanner CSV #1 export from 23 Oct, in the same layout.
    private val verticalCsv = "\"SECONDS\";\"PID\";\"VALUE\";\"UNITS\";\n" +
        "\"71850.3056771\";\"Vehicle speed\";\"0\";\"km/h\";\r\n" +
        "\"71850.7440107\";\"Intake manifold absolute pressure\";\"7.687000114371001\";\"psi\";\r\n" +
        "\"71852.8694881\";\"Engine coolant temperature\";\"65\";\"℃\";\r\n" +
        "\"71860.0\";\"Vehicle speed\";\"36\";\"km/h\";\r\n" +
        "\"71870.0\";\"Vehicle speed\";\"36\";\"km/h\";\r\n" +
        "\"71870.0\";\"Distance travelled\";\"0.2\";\"km\";\r\n" +
        "\"71870.0\";\"Fuel used\";\"0.02\";\"L\";\r\n"

    private fun midnight(y: Int, m: Int, d: Int) = Calendar.getInstance().apply {
        set(y, m - 1, d, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun importsCarScannerVerticalCsv() {
        val log = CsvImport.parse(verticalCsv.reader().buffered(), "2026-10-23_19-57-16.csv")
        assertEquals(7, log.samples.size)
        assertEquals(midnight(2026, 10, 23) + 71_850_305L, log.startMs)
        val coolant = log.samples.first { it.name == "Engine coolant temperature" }
        assertEquals("°C", coolant.unit)
        assertEquals(65.0, coolant.value, 1e-9)
        assertEquals("psi", log.samples.first { it.name.startsWith("Intake") }.unit)
    }

    @Test
    fun importSummaryUsesCarScannerTotals() {
        val log = CsvImport.parse(verticalCsv.reader().buffered(), "2026-10-23_19-57-16.csv")
        val trip = ImportSummary.trip(log, 390.0)
        assertEquals(0.2, trip.distanceKm, 1e-9)
        assertEquals(0.02, trip.fuelL, 1e-9)
        assertEquals(0.02 * 390.0, trip.cost, 1e-9)
        assertEquals(36.0, trip.maxSpeed, 1e-9)
        assertTrue(trip.driveS > 0)
    }

    @Test
    fun importsHorizontalCsv() {
        val csv = "\"Time\";\"Engine RPM (rpm)\";\"Vehicle speed (km/h)\"\n" +
            "\"19:57:30\";\"800\";\"0\"\n\"19:57:31\";\"1500\";\"12\"\n"
        val log = CsvImport.parse(csv.reader().buffered(), "2026-10-23_19-57-16.csv")
        assertEquals(4, log.samples.size)
        assertEquals("rpm", log.samples.first { it.name == "Engine RPM" }.unit)
        assertEquals(midnight(2026, 10, 23) + (19 * 3600 + 57 * 60 + 30) * 1000L, log.startMs)
    }

    @Test
    fun exportThenImportGivesTheSameData() {
        val start = midnight(2026, 10, 24) + 8 * 3600_000L
        val speed = Series(Channel(1, "Vehicle speed", "km/h"), longArrayOf(start, start + 1000), floatArrayOf(0f, 12f))
        val rpm = Series(Channel(2, "Engine RPM", "rpm"), longArrayOf(start, start + 1000), floatArrayOf(800f, 1500f))
        val rec = RecordingRow(1, start, start + 1000, "Live", "Drive", null)
        val dir = createTempDir()
        val file = CsvExport.vertical(dir, rec, listOf(speed, rpm))
        val back = CsvImport.parse(file.bufferedReader(), file.name)
        assertEquals(4, back.samples.size)
        assertEquals(start, back.startMs)
        assertEquals(1500.0, back.samples.first { it.name == "Engine RPM" && it.tMs == start + 1000 }.value, 1e-3)
        dir.deleteRecursively()
    }

    @Test
    fun decodesRpmReply() {
        val reply = com.abeernch.civicobd.obd.ElmReply("010C", listOf("410C1AF8"), 80, null, false)
        assertEquals(1726.0, Pids.decode(reply, Pids.BY_CODE.getValue(0x0C))!!, 1e-9)
    }

    @Test
    fun simulatedCarAnswersLikeTheRealOne() = runBlocking {
        val sim = SimTransport()
        sim.connect()
        val elm = ElmSession(sim) {}
        elm.send("ATZ")
        val first = elm.send("0100")
        assertEquals(listOf(0x01, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x13), Pids.supported(first, 0x00))
        assertEquals("A3", elm.send("ATDPN").text)
        val rpm = Pids.decode(elm.send("010C1"), Pids.BY_CODE.getValue(0x0C))
        assertNotNull(rpm)
        assertTrue(rpm!! in 700.0..900.0) // idling at the start of the loop
    }

    @Test
    fun gearAndFuelCut() {
        val engine = MetricEngine()
        var t = 1_000_000L
        // Learn closed throttle at idle first.
        engine.step(mapOf(0x0C to 800.0, 0x0D to 0.0, 0x11 to 9.8, 0x0B to 30.0, 0x0F to 32.0), t, 1.0)
        // Cruising at 72 km/h in fifth: 72 / 34.8 * 1000 rpm.
        var d = engine.step(mapOf(0x0C to 2069.0, 0x0D to 72.0, 0x11 to 16.0, 0x0B to 42.0, 0x0F to 32.0), t + 500, 1.0)
        d = engine.step(mapOf(0x0C to 2069.0, 0x0D to 72.0, 0x11 to 16.0, 0x0B to 42.0, 0x0F to 32.0), t + 1000, 1.0)
        assertEquals(5, d.gear)
        assertTrue(d.instantKmL!! in 10.0..40.0)
        // Coasting in gear with the throttle shut: fuel cut.
        t += 2000
        d = engine.step(mapOf(0x0C to 1950.0, 0x0D to 40.0, 0x11 to 9.8, 0x0B to 22.0, 0x0F to 32.0), t, 1.0)
        assertTrue(d.fuelCut)
        assertEquals(0.0, d.fuelLph!!, 1e-9)
    }

    @Test
    fun parsesFaultCodes() {
        val codes = com.abeernch.civicobd.core.Diagnostics.parseCodes(listOf("430133014200", "430000000000"), 3)
        assertEquals(listOf("P0133", "P0142"), codes.map { it.code })
        assertEquals("C0123", com.abeernch.civicobd.core.Diagnostics.decode("4123")!!.code)
        assertEquals("U0100", com.abeernch.civicobd.core.Diagnostics.decode("C100")!!.code)
        assertTrue(codes[0].description.contains("Oxygen sensor"))
    }

    @Test
    fun simulatedCarReportsAndClearsACode() = runBlocking {
        val sim = SimTransport()
        sim.connect()
        val elm = ElmSession(sim) {}
        val before = com.abeernch.civicobd.core.Diagnostics.parseCodes(elm.send("03").lines, 3)
        assertEquals(listOf("P0456"), before.map { it.code })
        assertTrue(elm.send("04").lines.any { it.startsWith("44") })
        val after = com.abeernch.civicobd.core.Diagnostics.parseCodes(elm.send("03").lines, 3)
        assertTrue(after.isEmpty())
    }

    @Test
    fun routeColoursStopsAndPrivacyTrim() {
        val pts = (0 until 120).map { i ->
            com.abeernch.civicobd.core.RoutePoint(
                t = 1_000_000L + i * 1000L, lat = 33.68 + i * 0.0001, lon = 73.04, km = i * 0.011,
                speed = if (i in 40..70) 0.0 else 40.0, kmPerL = if (i in 40..70) null else 10.0 + i % 10,
                fuelRate = 1.5, rpm = 2000.0, throttle = 15.0, accel = 0.0, gear = 3.0, altitude = 500.0,
            )
        }
        val stops = com.abeernch.civicobd.core.Route.stops(pts)
        assertEquals(1, stops.size)
        assertTrue(stops[0].seconds >= 30)
        val green = com.abeernch.civicobd.core.Route.color(com.abeernch.civicobd.core.ColorScale.RedYellowGreen, 1f)
        val red = com.abeernch.civicobd.core.Route.color(com.abeernch.civicobd.core.ColorScale.RedYellowGreen, 0f)
        assertTrue((green shr 8 and 0xFF) > (green shr 16 and 0xFF)) // more green than red
        assertTrue((red shr 16 and 0xFF) > (red shr 8 and 0xFF))     // more red than green
        val trimmed = com.abeernch.civicobd.core.Route.trimForPrivacy(pts, 200.0)
        assertTrue(trimmed.first().km >= 0.2 && trimmed.last().km <= pts.last().km - 0.2)
    }

    @Test
    fun readsPetrolPriceFromPageText() {
        val html = "<table><tr><td><a>Petrol Super Price in Pakistan</a></td><td>377.71 Rs./Ltr</td></tr></table>"
        assertEquals(377.71, com.abeernch.civicobd.core.PetrolPrice.parse(html)!!, 1e-9)
        assertEquals(null, com.abeernch.civicobd.core.PetrolPrice.parse("<p>no prices today</p>"))
    }

    private fun createTempDir(): File = File.createTempFile("civic", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    /** Build 32 crashed here: "12% better" was passed through String.format, which read "% b" as a code. */
    @Test
    fun tripSummaryTextWithPercentDoesNotCrash() {
        val t = com.abeernch.civicobd.core.TripRow(
            id = 1, startMs = 0, endMs = 24 * 60_000, distanceKm = 11.0, fuelL = 0.98, cost = 381.0,
            driveS = 1260.0, idleS = 90.0, maxSpeed = 65.0,
        )
        val better = com.abeernch.civicobd.core.TripSummary(t, averageKmL = 10.0)
        val (line, more) = better.notificationText()
        assertEquals("11.0 km  ·  11.2 km/L  ·  Rs 381  ·  24 min", line)
        assertTrue(more, more.startsWith("Economy 12% better than your average (10.0 km/L)."))
        val worse = com.abeernch.civicobd.core.TripSummary(t, averageKmL = 14.0).notificationText().second
        assertTrue(worse, worse.contains("% below your average"))
    }

    @Test
    fun serviceDueByDistanceOrTimeWhicheverFirst() {
        val day = 86_400_000L
        val now = 1_800_000_000_000L
        val oil = com.abeernch.civicobd.core.ServiceItem("oil", "Engine oil", 5000.0, 6, doneKm = 195_000.0, doneMs = now - 30 * day)
        val s = com.abeernch.civicobd.core.Maintenance.status(oil, 199_251.0, now)
        assertEquals(749.0, s.dueInKm!!, 0.5)
        assertEquals(150, s.dueInDays)
        assertTrue(!s.overdue && !s.soon)
        val late = com.abeernch.civicobd.core.Maintenance.status(oil, 200_300.0, now)
        assertTrue(late.overdue)
        assertEquals("overdue by 300 km or in 5 months", com.abeernch.civicobd.core.Maintenance.dueText(late))
        // A reading typed into the wrong job (24 Oct recording): above the odometer, so flagged, not shown as due.
        val wrong = com.abeernch.civicobd.core.Maintenance.status(oil.copy(doneKm = 1_935_000.0), 199_277.0, now)
        assertTrue(wrong.badReading && !wrong.known)
    }

    @Test
    fun drivesBetweenTheSamePlacesFormOneRoute() {
        fun trip(id: Long, start: Long) = com.abeernch.civicobd.core.TripRow(id = id, startMs = start, endMs = start + 1_800_000, distanceKm = 19.0, fuelL = 1.4)
        val home = 33.6500 to 73.0500
        val work = 33.6000 to 73.1000
        val ends = listOf(
            com.abeernch.civicobd.core.TripEnds(trip(1, 1), home.first, home.second, work.first, work.second),
            com.abeernch.civicobd.core.TripEnds(trip(2, 2), home.first + 0.001, home.second, work.first, work.second + 0.001),
            com.abeernch.civicobd.core.TripEnds(trip(3, 3), work.first, work.second, home.first, home.second), // the way back
            com.abeernch.civicobd.core.TripEnds(trip(4, 4), 33.7, 73.2, 33.71, 73.21), // a one-off
        )
        val groups = com.abeernch.civicobd.core.RouteGroups.build(ends, mapOf(1L to "Home to office"))
        assertEquals(1, groups.size)
        assertEquals("Home to office", groups[0].name)
        assertEquals(listOf(2L, 1L), groups[0].trips.map { it.id })
    }

    /**
     * From the 24 Oct drive log: 0100 timed out, its answer came late, and every later reply was
     * read one command behind ("0100 -> 11.9V"). The session must wait out the late answer.
     */
    @Test
    fun lateReplyIsNotTakenAsTheNextAnswer() = runBlocking {
        val link = SlowAdapter(lateMs = 400)
        val elm = ElmSession(link) { }
        val first = elm.send("0100", timeoutMs = 100)
        assertTrue(first.timedOut)
        val volts = elm.send("ATRV", timeoutMs = 2_000)
        assertEquals("14.2V", volts.text)
        val again = elm.send("0100", timeoutMs = 2_000)
        assertTrue(Pids.supported(again, 0).isNotEmpty())
    }

    /** A fake adapter: "0100" answers after a delay, everything else at once. */
    private class SlowAdapter(private val lateMs: Long) : com.abeernch.civicobd.obd.Transport {
        private val toPhone = java.util.concurrent.LinkedBlockingQueue<Int>()
        private var first0100 = true
        // One worker, like the real chip: a command is answered only after the one before it.
        private val chip = java.util.concurrent.Executors.newSingleThreadExecutor()
        override val input = object : java.io.InputStream() {
            override fun read(): Int = toPhone.take()
            override fun available(): Int = toPhone.size
        }
        private val line = StringBuilder()
        override val output = object : java.io.OutputStream() {
            override fun write(b: Int) {
                val c = b.toChar()
                if (c != '\r') { line.append(c); return }
                val cmd = line.toString().trim(); line.clear()
                val answer = when (cmd) {
                    "0100" -> "41 00 BE 1F B8 10"
                    "ATRV" -> "14.2V"
                    else -> "OK"
                }
                val wait = if (cmd == "0100" && first0100) lateMs else 0L
                if (cmd == "0100") first0100 = false
                chip.execute {
                    Thread.sleep(wait)
                    ("$answer\r\r>").forEach { toPhone.put(it.code) }
                }
            }
        }
        override fun connect() {}
        override fun close() {}
    }
}
