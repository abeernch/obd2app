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
    fun periodsShowTheirDatesAndStep() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 28, 14, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val W = com.abeernch.civicobd.core.Windows
        // This week runs Monday to Sunday, every day a bar.
        val week = W.of(com.abeernch.civicobd.core.Period.Week, 0, now)
        assertEquals("26 Oct – 1 Nov 2026", week.title)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), week.slots.map { it.label })
        assertEquals("19 Oct – 25 Oct 2026", W.of(com.abeernch.civicobd.core.Period.Week, -1, now).title)
        // Months by name, stepping back across a year end.
        assertEquals("October 2026", W.of(com.abeernch.civicobd.core.Period.Month, 0, now).title)
        assertEquals(31, W.of(com.abeernch.civicobd.core.Period.Month, 0, now).slots.size)
        // Every day is named: the wider bars leave room, and the chart thins the dates only if they collide.
        assertEquals(listOf("1", "2", "3"), W.of(com.abeernch.civicobd.core.Period.Month, 0, now).slots.take(3).map { it.label })
        assertEquals("September 2026", W.of(com.abeernch.civicobd.core.Period.Month, -1, now).title)
        assertEquals(30, W.of(com.abeernch.civicobd.core.Period.Month, -1, now).slots.size)
        assertEquals("December 2025", W.of(com.abeernch.civicobd.core.Period.Month, -10, now).title)
        // A year: twelve months, and the year before.
        val year = W.of(com.abeernch.civicobd.core.Period.Year, 0, now)
        assertEquals("2026", year.title)
        assertEquals(12, year.slots.size)
        assertEquals("Jan", year.slots.first().label)
        assertEquals("2025", W.of(com.abeernch.civicobd.core.Period.Year, -1, now).title)
        // Rolling windows step by their own length.
        assertEquals("22 Oct – 28 Oct 2026", W.of(com.abeernch.civicobd.core.Period.Days7, 0, now).title)
        assertEquals("15 Oct – 21 Oct 2026", W.of(com.abeernch.civicobd.core.Period.Days7, -1, now).title)
        assertEquals("Wednesday, 28 Oct 2026", W.of(com.abeernch.civicobd.core.Period.Today, 0, now).title)
        assertEquals("Tuesday, 27 Oct 2026", W.of(com.abeernch.civicobd.core.Period.Yesterday, 0, now).title)
    }

    @Test
    fun economyColourRunsRedToGreen() {
        val c = { k: Double -> com.abeernch.civicobd.ui.economyColor(k, dark = true) }
        // The muted shades of the redesigned Drive tab.
        assertEquals(androidx.compose.ui.graphics.Color(0xFFE0716A), c(5.0))   // poor: red
        assertEquals(androidx.compose.ui.graphics.Color(0xFFE7B65C), c(13.0))  // middle: amber
        assertEquals(androidx.compose.ui.graphics.Color(0xFF62C88A), c(22.0))  // good: green
        assertEquals(androidx.compose.ui.graphics.Color(0xFF62C88A), com.abeernch.civicobd.ui.economyColor(null, fuelCut = true, dark = true))
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

    @Test
    fun routeOrdinalsReadNaturally() {
        val r = com.abeernch.civicobd.core.RouteContexts
        assertEquals("1st", r.ordinal(1))
        assertEquals("2nd", r.ordinal(2))
        assertEquals("3rd", r.ordinal(3))
        assertEquals("4th", r.ordinal(4))
        assertEquals("11th", r.ordinal(11))
        assertEquals("12th", r.ordinal(12))
        assertEquals("13th", r.ordinal(13))
        assertEquals("14th", r.ordinal(14))
        assertEquals("21st", r.ordinal(21))
        assertEquals("102nd", r.ordinal(102))
        assertEquals("111th", r.ordinal(111))
    }

    @Test
    fun economyComparisonPrefersTheRoute() {
        val r = com.abeernch.civicobd.core.RouteContexts
        // Against the route's usual figure when there is one.
        val better = r.comparison(12.6, 11.8, 10.0)!!
        assertEquals("6% better than your usual 11.8 on this route", better.first)
        assertEquals(true, better.second)
        // Otherwise against the 30-day average.
        val worse = r.comparison(9.0, null, 10.0)!!
        assertEquals("10% below your 30-day average of 10.0", worse.first)
        assertEquals(false, worse.second)
        // Within 2 % is level.
        val level = r.comparison(10.1, 10.0, null)!!
        assertEquals("Level with your usual 10.0 on this route", level.first)
        assertEquals(null, level.second)
        // Nothing to compare with, or no economy for this drive.
        assertEquals(null, r.comparison(12.0, null, null))
        assertEquals(null, r.comparison(null, 11.0, 10.0))
    }

    @Test
    fun driveClockFormats() {
        assertEquals("0:00", com.abeernch.civicobd.ui.clockTime(0.0))
        assertEquals("24:18", com.abeernch.civicobd.ui.clockTime(24 * 60 + 18.4))
        assertEquals("59:59", com.abeernch.civicobd.ui.clockTime(3599.0))
        assertEquals("1:05:32", com.abeernch.civicobd.ui.clockTime(3600 + 5 * 60 + 32.0))
        assertEquals("0:00", com.abeernch.civicobd.ui.clockTime(-5.0))
    }

    // Fill-ups, newest first as the log lists them. Day 1 full, day 5 partial, day 9 full (it set the correction).
    private val day = 86_400_000L
    private val first = com.abeernch.civicobd.core.Fillup(id = 1, timeMs = 1 * day, litres = 40.0, price = 260.0, full = true, odometer = 50_000.0)
    private val partial = com.abeernch.civicobd.core.Fillup(id = 2, timeMs = 5 * day, litres = 10.0, price = 262.0, full = false, odometer = null)
    // The app counted 30 L between day 1 and day 9; the pump put in 10 + 23 = 33 L, so 1.0 became 1.1.
    private val newest = com.abeernch.civicobd.core.Fillup(
        id = 3, timeMs = 9 * day, litres = 23.0, price = 265.0, full = true, odometer = 50_400.0,
        appLitres = 30.0, factorBefore = 1.0, factorAfter = 1.1,
    )
    private val counted: (Long, Long) -> com.abeernch.civicobd.core.Counted = { _, _ -> com.abeernch.civicobd.core.Counted(30.0) }

    @Test
    fun fillupComparisonCountsPartialFillsInBetween() {
        val c = com.abeernch.civicobd.core.compare(newest, listOf(partial, first), counted)!!
        assertEquals(30.0, c.counted, 1e-9)
        assertEquals(33.0, c.pumped, 1e-9)
        assertEquals(1.1, c.factorFrom(1.0), 1e-9)
        // A partial fill, or a full tank with none before it, makes no comparison.
        assertEquals(null, com.abeernch.civicobd.core.compare(partial, listOf(first), counted))
        assertEquals(null, com.abeernch.civicobd.core.compare(first, emptyList(), counted))
    }

    @Test
    fun correctingTheNewestFullTankRedoesItsCorrection() {
        // 23 L was a typo for 26 L: (10 + 26) / 30 from the 1.0 it started from.
        val change = com.abeernch.civicobd.core.changeFillup(
            newest, newest.copy(litres = 26.0), listOf(partial, first), factorNow = 1.1, priceNow = 265.0,
            odometerAtMs = 9 * day, countedBetween = counted,
        )
        assertEquals(1.2, change.factor!!, 1e-9)
        assertEquals(1.2, change.saved!!.factorAfter!!, 1e-9)
        assertEquals(1.0, change.saved!!.factorBefore!!, 1e-9)
        assertEquals(9 * day, change.saved!!.timeMs) // the time stays as logged
        // Made a partial fill instead: no comparison any more, so the correction goes back to 1.0.
        val asPartial = com.abeernch.civicobd.core.changeFillup(
            newest, newest.copy(full = false), listOf(partial, first), 1.1, 265.0, 9 * day, counted,
        )
        assertEquals(1.0, asPartial.factor!!, 1e-9)
        assertEquals(null, asPartial.saved!!.factorAfter)
    }

    @Test
    fun deletingTheNewestFullTankUndoesItsCorrectionAndPrice() {
        val change = com.abeernch.civicobd.core.changeFillup(newest, null, listOf(partial, first), 1.1, 265.0, 9 * day, counted)
        assertEquals(null, change.saved)
        assertEquals(1.0, change.factor!!, 1e-9)
        assertEquals(262.0, change.price!!, 1e-9) // the price from the fill-up before it
        // An older record without the starting correction kept: worked back from its comparison.
        val old = newest.copy(factorBefore = null)
        val back = com.abeernch.civicobd.core.changeFillup(old, null, listOf(partial, first), 1.1, 265.0, 9 * day, counted)
        assertEquals(1.0, back.factor!!, 1e-9)
    }

    @Test
    fun fillupChangesLeaveAHandSetCorrectionAndOlderTanksAlone() {
        // The correction was typed in by hand after the fill-up: neither a correction nor a delete touches it.
        val handSet = com.abeernch.civicobd.core.changeFillup(newest, newest.copy(litres = 26.0), listOf(partial, first), 1.05, 265.0, 9 * day, counted)
        assertEquals(null, handSet.factor)
        assertEquals(null, com.abeernch.civicobd.core.changeFillup(newest, null, listOf(partial, first), 1.05, 265.0, 9 * day, counted).factor)
        // The first full tank is older than the newest one: correcting or deleting it changes no setting.
        val older = com.abeernch.civicobd.core.changeFillup(first, first.copy(litres = 41.0), listOf(newest, partial), 1.1, 265.0, 9 * day, counted)
        assertEquals(null, older.factor)
        assertEquals(null, older.price)
        assertEquals(41.0, older.saved!!.litres, 1e-9)
        assertEquals(null, com.abeernch.civicobd.core.changeFillup(first, null, listOf(newest, partial), 1.1, 265.0, 9 * day, counted).factor)
    }

    @Test
    fun correctingTheNewestFillupsPriceAndOdometer() {
        val change = com.abeernch.civicobd.core.changeFillup(
            newest, newest.copy(price = 268.0, odometer = 50_450.0), listOf(partial, first), 1.1, 265.0, 9 * day, counted,
        )
        assertEquals(268.0, change.price!!, 1e-9)
        assertEquals(50_450.0, change.odometerKm!!, 1e-9)
        assertEquals(null, change.factor) // litres unchanged, so the correction stands
        // A newer odometer reading (typed in Settings since) is not replaced by an older fill-up's.
        val later = com.abeernch.civicobd.core.changeFillup(newest, newest.copy(odometer = 50_450.0), listOf(partial, first), 1.1, 265.0, 10 * day, counted)
        assertEquals(null, later.odometerKm)
    }

    // ---- Drives added by hand, carpooling share, tanks

    private fun drive(id: Long, startDay: Double, km: Double, litres: Double, manual: Boolean = false, shared: Double = 0.0, minutes: Double = 30.0) =
        com.abeernch.civicobd.core.TripRow(
            id = id, startMs = (startDay * day).toLong(), endMs = (startDay * day).toLong() + (minutes * 60_000).toLong(),
            distanceKm = km, fuelL = litres, cost = litres * 300.0, driveS = minutes * 60, manual = manual, shared = shared,
        )

    @Test
    fun handAddedDrivesCountInTotalsButNotInEconomy() {
        val recorded = drive(1, 1.0, 100.0, 8.0, minutes = 120.0)   // 12.5 km/L, 50 km/h
        val byHand = drive(2, 2.0, 60.0, 2.0, manual = true, minutes = 30.0) // an estimate: 30 km/L, 120 km/h
        val s = com.abeernch.civicobd.core.Summary.of(listOf(recorded, byHand))
        assertEquals(2, s.trips)
        assertEquals(160.0, s.distanceKm, 1e-9)
        assertEquals(10.0, s.fuelL, 1e-9)
        assertEquals(3000.0, s.cost, 1e-9)
        assertEquals(1, s.handAdded)
        assertEquals(12.5, s.kmPerL!!, 1e-9)  // the recorded drive alone
        assertEquals(50.0, s.avgSpeed!!, 1e-9)
        // Its own page shows no economy or speed either.
        assertEquals(null, com.abeernch.civicobd.core.TripSummary(byHand, 12.5).kmPerL)
        assertEquals(null, com.abeernch.civicobd.core.TripSummary(byHand, 12.5).avgSpeed)
        // Only drives added by hand: no economy at all, rather than an estimate's.
        assertEquals(null, com.abeernch.civicobd.core.Summary.of(listOf(byHand)).kmPerL)
    }

    @Test
    fun carpoolingShareComesOffTheCost() {
        val trip = drive(1, 1.0, 25.0, 2.0, shared = 800.0) // fuel Rs 600, carpooling share Rs 800
        assertEquals(-200.0, trip.netCost, 1e-9)
        val s = com.abeernch.civicobd.core.Summary.of(listOf(trip, drive(2, 2.0, 25.0, 2.0)))
        assertEquals(1200.0, s.cost, 1e-9)    // fuel
        assertEquals(800.0, s.shared, 1e-9)
        assertEquals(400.0, s.netCost, 1e-9)
    }

    @Test
    fun aStretchWithAHandAddedDriveDoesNotCorrectTheFuelCount() {
        val withHand: (Long, Long) -> com.abeernch.civicobd.core.Counted = { _, _ -> com.abeernch.civicobd.core.Counted(30.0, handAdded = true) }
        val c = com.abeernch.civicobd.core.compare(newest, listOf(partial, first), withHand)!!
        assertEquals(false, c.calibrates)
        // Correcting such a fill-up leaves the correction alone, and keeps the comparison for the record.
        val change = com.abeernch.civicobd.core.changeFillup(newest.copy(factorAfter = null, factorBefore = null), newest.copy(litres = 26.0),
            listOf(partial, first), 1.0, 265.0, 9 * day, withHand)
        assertEquals(null, change.factor)
        assertEquals(30.0, change.saved!!.appLitres!!, 1e-9)
        assertEquals(null, change.saved!!.factorAfter)
    }

    @Test
    fun eachFullTankGivesItsEconomyByThePump() {
        // 412 km between the first and the newest full tank; the pump put back 10 + 23 = 33 L.
        val map = com.abeernch.civicobd.core.stretches(listOf(newest, partial, first)) { _, _ -> 412.0 to false }
        val tank = map[newest.id]!!
        assertEquals(412.0, tank.km, 1e-9)
        assertEquals(33.0, tank.pumpedL, 1e-9)
        assertEquals(412.0 / 33.0, tank.kmPerL!!, 1e-9)
        // The first full tank and a partial fill close no tank.
        assertEquals(null, map[first.id])
        assertEquals(null, map[partial.id])
    }

    @Test
    fun theTankCountsTheDriveInProgressOnce() {
        val full = com.abeernch.civicobd.core.Fillup(id = 1, timeMs = 1 * day, litres = 40.0, price = 300.0, full = true, odometer = null)
        val done = drive(10, 2.0, 120.0, 10.0)
        // The drive in progress is saved every few seconds: its row (6 L so far) and its live total (6.2 L).
        val inProgress = drive(11, 3.0, 70.0, 6.0)
        val tank = com.abeernch.civicobd.core.tankOf(listOf(full), listOf(inProgress, done), 50.0, 6.2, 72.0, activeTripId = 11)
        assertEquals(16.2, tank.usedL, 1e-9)                // 10 + 6.2, not 10 + 6 + 6.2
        assertEquals(50.0 - 16.2, tank.leftL!!, 1e-9)
        assertEquals((120.0 + 72.0) / 16.2, tank.kmPerL!!, 1e-9)
        // A drive added by hand: its fuel left the tank, but its economy is an estimate.
        val byHand = drive(12, 2.5, 60.0, 4.0, manual = true)
        val withHand = com.abeernch.civicobd.core.tankOf(listOf(full), listOf(byHand, done), 50.0, 0.0, 0.0, activeTripId = -1)
        assertEquals(14.0, withHand.usedL, 1e-9)
        assertEquals(12.0, withHand.kmPerL!!, 1e-9)        // 120 km on 10 L, the recorded drive
        // And the odometer, the same way.
        assertEquals(50_000.0 + 120.0 + 72.0, com.abeernch.civicobd.core.Car.odometerOf(50_000.0, listOf(inProgress, done), 72.0, 11), 1e-9)
    }

    @Test
    fun handAddedDriveFigures() {
        val hd = com.abeernch.civicobd.core.HandDrives
        // Odometer now: what it shows, less what the app expects.
        assertEquals(56.0, hd.missingKm(123_456.0, 123_400.0)!!, 1e-9)
        assertEquals(null, hd.missingKm(123_400.0, 123_400.0))   // nothing unrecorded
        assertEquals(null, hd.missingKm(123_300.0, 123_400.0))   // behind the app's count: nothing to add
        assertEquals(null, hd.missingKm(200_000.0, 123_400.0))   // a mistyped reading
        assertEquals(56.0, hd.between(123_400.0, 123_456.0)!!, 1e-9)
        assertEquals(null, hd.between(123_456.0, 123_400.0))
        // Fuel from recent economy, at the price paid at the time.
        assertEquals(4.0, hd.estimateFuel(50.0, 12.5), 1e-9)
        val fills = listOf(newest, partial, first)                // 265 on day 9, 262 on day 5, 260 on day 1
        assertEquals(262.0, hd.priceAt(fills, 6 * day, 300.0), 1e-9)
        assertEquals(265.0, hd.priceAt(fills, 9 * day, 300.0), 1e-9)
        assertEquals(300.0, hd.priceAt(fills, day / 2, 300.0), 1e-9) // before any fill-up: the price in use
        // Recent economy: the 30 days before, else every recorded drive; never from drives added by hand.
        val older = listOf(drive(1, 1.0, 100.0, 8.0), drive(2, 2.0, 60.0, 1.0, manual = true))
        assertEquals(12.5, hd.recentKmPerL(emptyList(), older)!!, 1e-9)
        assertEquals(null, hd.recentKmPerL(emptyList(), listOf(drive(2, 2.0, 60.0, 1.0, manual = true))))
        // The drive saved: estimated fuel is flagged, entered fuel is not; minutes set its end.
        val est = hd.build(startMs = 10 * day, km = 50.0, minutes = 45.0, litres = null, estimatedL = 4.0, pricePerL = 300.0,
            note = "  Brother took the car ", odoStart = null, odoEnd = null)
        assertEquals(true, est.manual)
        assertEquals(true, est.fuelEstimated)
        assertEquals(1200.0, est.cost, 1e-9)
        assertEquals(10 * day + 45 * 60_000L, est.endMs)
        assertEquals("Brother took the car", est.note)
        val typed = hd.build(startMs = 10 * day, km = 50.0, minutes = null, litres = 3.5, estimatedL = 4.0, pricePerL = 300.0,
            note = " ", odoStart = 1.0, odoEnd = 51.0)
        assertEquals(false, typed.fuelEstimated)
        assertEquals(3.5, typed.fuelL, 1e-9)
        assertEquals(typed.startMs, typed.endMs)
        assertEquals(null, typed.note)
        // The odometer kept: a reading off the dashboard now always; an after-reading only if as recent.
        val D = com.abeernch.civicobd.core.HandDrives.Distance.OdometerNow
        val from = 10 * day - 3_600_000
        assertEquals(123_456.0 to 20 * day, hd.odometerToKeep(D, 123_456.0, null, from, 10 * day, 20 * day, 15 * day))
        assertEquals(51.0 to 10 * day, hd.odometerToKeep(com.abeernch.civicobd.core.HandDrives.Distance.BeforeAfter, null, 51.0, from, 10 * day, 20 * day, 9 * day))
        assertEquals(null, hd.odometerToKeep(com.abeernch.civicobd.core.HandDrives.Distance.BeforeAfter, null, 51.0, from, 10 * day, 20 * day, 12 * day))
        assertEquals(null, hd.odometerToKeep(com.abeernch.civicobd.core.HandDrives.Distance.Kilometres, null, null, from, 10 * day, 20 * day, 9 * day))
    }

    @Test
    fun thisTankStepsBackTankByTank() {
        val w = com.abeernch.civicobd.core.Windows
        val now = 20 * day + 13 * 3_600_000L
        // No full tank yet: nothing to show, and it says why.
        val none = w.of(com.abeernch.civicobd.core.Period.Tank, 0, now)
        assertEquals(true, none.empty != null)
        assertEquals(false, none.canBack)
        val starts = listOf(17 * day + 22 * 3_600_000L, 10 * day + 8 * 3_600_000L) // full tanks, newest first
        val current = w.of(com.abeernch.civicobd.core.Period.Tank, 0, now, tankStarts = starts)
        assertEquals(starts[0], current.startMs)
        assertEquals(now + 1, current.endMs)
        assertEquals(true, current.title.startsWith("Since "))
        assertEquals(true, current.canBack)
        assertEquals(null, current.empty)
        // A bar for each day since, covering the tank from its start to now (days are local, so how many
        // depends on where midnight falls).
        assertEquals(true, current.slots.first().startMs <= starts[0])
        assertEquals(true, current.slots.last().endMs >= now)
        assertEquals(true, current.slots.all { it.endMs - it.startMs in 23 * 3_600_000L..25 * 3_600_000L })
        val before = w.of(com.abeernch.civicobd.core.Period.Tank, -1, now, tankStarts = starts)
        assertEquals(starts[1], before.startMs)
        assertEquals(starts[0], before.endMs)
        assertEquals(true, before.title.startsWith("Tank of "))
        assertEquals(false, before.canBack)                // the first full tank
        // A tank filled today is charted by hour.
        val todays = w.of(com.abeernch.civicobd.core.Period.Tank, 0, now, tankStarts = listOf(now - 3_600_000L))
        assertEquals(24, todays.slots.size)
        assertEquals(com.abeernch.civicobd.core.Period.Tank, com.abeernch.civicobd.core.Period.entries.first())
    }

    @Test
    fun aReadingKeptAfterAHandAddedDriveDoesNotCountItTwice() {
        val H = com.abeernch.civicobd.core.HandDrives
        val car = com.abeernch.civicobd.core.Car
        val start = 10 * day
        // Before and after, with no time given: the drive starts and ends at once. The after-reading is kept
        // for just after its start, so the drive is not added on top of it again.
        val (odo, at) = H.odometerToKeep(com.abeernch.civicobd.core.HandDrives.Distance.BeforeAfter, null, 50_025.0, start, start, 11 * day, inUseAtMs = 5 * day)!!
        assertEquals(50_025.0, odo, 1e-9)
        assertTrue(at > start)
        val handDrive = drive(7, 10.0, 25.0, 2.0, manual = true, minutes = 0.0)
        val since = listOf(handDrive).filter { it.startMs >= at }
        assertEquals(50_025.0, car.odometerOf(odo, since, 0.0, -1), 1e-9)
        // An after-reading older than the one in use leaves it alone.
        assertEquals(null, H.odometerToKeep(com.abeernch.civicobd.core.HandDrives.Distance.BeforeAfter, null, 50_025.0, start, start + 60_000, 11 * day, inUseAtMs = 12 * day))
        // Read off the dashboard now: kept now, after the drive.
        val (odoNow, atNow) = H.odometerToKeep(com.abeernch.civicobd.core.HandDrives.Distance.OdometerNow, 50_100.0, null, start, start, 11 * day, inUseAtMs = 5 * day)!!
        assertEquals(50_100.0 to 11 * day, odoNow to atNow)
        // Typed in kilometres: the reading in use stays.
        assertEquals(null, H.odometerToKeep(com.abeernch.civicobd.core.HandDrives.Distance.Kilometres, null, null, start, start, 11 * day, inUseAtMs = 5 * day))
    }

    @Test
    fun aReadingTakenDuringARecordedDriveCountsThatDriveOnce() {
        val car = com.abeernch.civicobd.core.Car
        // Typed 20 minutes into a drive that has covered 15 km so far, while it is still recorded.
        val live = com.abeernch.civicobd.core.TripTotals(id = 9, startMs = 10 * day, distanceKm = 15.0)
        val now = 10 * day + 20 * 60_000
        val (base, at) = car.readingAt(80_015.0, now, live)
        assertEquals(80_000.0 to 10 * day, base to at)
        // The drive is then counted from its live total: the odometer shows what was typed, and grows with it.
        assertEquals(80_015.0, car.odometerOf(base, emptyList(), 15.0, 9), 1e-9)
        assertEquals(80_040.0, car.odometerOf(base, emptyList(), 40.0, 9), 1e-9)
        // Once it is saved and over, it counts once, from its row.
        val ended = drive(9, 10.0, 40.0, 3.0, minutes = 50.0)
        assertEquals(80_040.0, car.odometerOf(base, listOf(ended), 0.0, -1), 1e-9)
        // With no drive in progress the reading is kept as typed, now.
        assertEquals(80_015.0 to now, car.readingAt(80_015.0, now, com.abeernch.civicobd.core.TripTotals()))
    }

    @Test
    fun aReadingTypedMidDriveByAnEarlierVersionIsRepaired() {
        val car = com.abeernch.civicobd.core.Car
        // As on the phone: typed 23 s into a 23 min 36 s drive of 13.76 km, which then dropped out of the odometer.
        val t = com.abeernch.civicobd.core.TripRow(id = 61, startMs = 1_000_000, endMs = 1_000_000 + 1_416_000, distanceKm = 13.758,
            driveS = 1065.6, idleS = 201.6)
        val typedAt = 1_000_000L + 23_000
        val (reading, from) = car.repairedReading(199_833.0, typedAt, listOf(t))!!
        assertEquals(t.startMs, from)
        assertEquals(199_833.0 - 13.758 * 23.0 / 1416.0, reading, 1e-9)
        // The drive now counts: 199,833 less the 0.2 km before the reading, plus the whole drive.
        assertEquals(199_846.53, car.odometerOf(reading, listOf(t), 0.0, -1), 0.01)
        // Repaired once: a reading at a drive's start is left alone.
        assertEquals(null, car.repairedReading(reading, from, listOf(t)))
        // A reading between drives, or inside a drive added by hand, needs nothing.
        assertEquals(null, car.repairedReading(199_833.0, t.endMs + 60_000, listOf(t)))
        assertEquals(null, car.repairedReading(199_833.0, typedAt, listOf(t.copy(manual = true))))
        // Drives merged across a gap span far more than their time moving: not split by time.
        val merged = t.copy(startMs = t.startMs - day, distanceKm = 433.0, driveS = 7 * 3600.0 + 1065.6)
        assertEquals(null, car.repairedReading(199_833.0, typedAt, listOf(merged)))
    }

    @Test
    fun mergingAcrossTheReadingKeepsTheOdometer() {
        val car = com.abeernch.civicobd.core.Car
        // The reading was kept for the start of today's drive; yesterday's drive is merged into it.
        val yesterday = drive(1, 9.0, 420.0, 30.0, minutes = 420.0)
        val today = drive(2, 10.0, 4.4, 0.3, minutes = 6.0)
        val at = today.startMs
        val before = car.odometerOf(49_996.0, listOf(today), 0.0, -1)          // 50,000.4
        val merged = yesterday.copy(endMs = today.endMs, distanceKm = 424.4)    // starts yesterday now
        val reading = car.readingAfterMerge(49_996.0, at, merged, listOf(yesterday, today))!!
        // The merged drive starts before the reading, so it is not added; today's distance is in the reading.
        assertEquals(before, car.odometerOf(reading, emptyList(), 0.0, -1), 1e-9)
        // Both before the reading, or both after it: nothing changes.
        assertEquals(null, car.readingAfterMerge(49_996.0, today.endMs + 60_000, merged, listOf(yesterday, today)))
        assertEquals(null, car.readingAfterMerge(49_996.0, yesterday.startMs, merged, listOf(yesterday, today)))
    }

    @Test
    fun aHandAddedDriveCannotEndLaterThanNowOrOverlapTheDriveBeingRecorded() {
        val H = com.abeernch.civicobd.core.HandDrives
        val now = 20 * day
        assertEquals(null, H.timeProblem(now - 3_600_000, 30.0, now, null))
        assertEquals(null, H.timeProblem(now - 3_600_000, null, now, null))
        assertEquals("That is later than now.", H.timeProblem(now + 60_000, null, now, null))
        assertTrue(H.timeProblem(now - 10 * 60_000, 30.0, now, null)!!.startsWith("With that much driving"))
        // A drive has been recorded since 40 minutes ago: this one must end before it started.
        val liveFrom = now - 40 * 60_000
        assertTrue(H.timeProblem(now - 30 * 60_000, null, now, liveFrom)!!.startsWith("A drive is being recorded since"))
        assertTrue(H.timeProblem(now - 60 * 60_000, 25.0, now, liveFrom)!!.startsWith("A drive is being recorded since"))
        assertEquals(null, H.timeProblem(now - 60 * 60_000, 15.0, now, liveFrom))
    }

    @Test
    fun theTripsExportCarriesTheShareAndDrivesAddedByHand() {
        val dir = kotlin.io.path.createTempDirectory("trips").toFile()
        val recorded = drive(1, 1.0, 20.0, 1.6, shared = 800.0)
        val byHand = drive(2, 2.0, 30.0, 2.0, manual = true).copy(fuelEstimated = true, passengers = 2, note = "Ali took it; \"short\" trip")
        val lines = CsvExport.trips(dir, listOf(byHand, recorded)).readLines()
        val head = lines[0].split(';')
        // The earlier columns keep their places; the new ones follow.
        assertEquals(listOf("Start", "End", "Distance km", "Fuel L", "Economy km/L", "Cost PKR"), head.take(6))
        assertEquals(listOf("Carpool passengers", "Carpooling share PKR", "Net cost PKR", "Added by hand", "Fuel estimated", "Note"), head.drop(9))
        val first = lines[1].split(';')            // oldest first: the recorded drive
        assertEquals("12.50", first[4])
        assertEquals("800", first[10])
        assertEquals("-320", first[11])            // fuel Rs 480 less Rs 800
        assertEquals("no", first[12])
        val second = lines[2]
        assertTrue(second.contains(";0.00;"))      // no economy for a drive added by hand
        assertTrue(second.endsWith(";yes;yes;\"Ali took it; \"\"short\"\" trip\""))
        dir.deleteRecursively()
    }

    // ---- A fill-up partway through a drive

    private fun speeds(vararg pts: Pair<Long, Float>) =
        com.abeernch.civicobd.core.Portions.Speeds(pts.map { it.first }.toLongArray(), pts.map { it.second }.toFloatArray())

    @Test
    fun aDriveIsSplitAtTheFillUpByItsSpeed() {
        val P = com.abeernch.civicobd.core.Portions
        val m = 60_000L
        // 10 min at 60 km/h (10 km), 5 min with the engine off at the pump, then 10 min at 30 km/h (5 km).
        val pts = mutableListOf<Pair<Long, Float>>()
        for (i in 0..600 step 2) pts += i * 1000L to 60f
        for (i in 900..1500 step 2) pts += i * 1000L to 30f
        val s = speeds(*pts.toTypedArray())
        val trip = com.abeernch.civicobd.core.TripRow(id = 9, startMs = 0, endMs = 25 * m, distanceKm = 15.0, fuelL = 1.2, cost = 450.0, driveS = 1200.0)
        val pump = 12 * m
        val after = P.share(trip, s, pump, Long.MAX_VALUE)
        assertEquals(1.0 / 3, after, 0.01)                   // 5 of the 15 km, though 13 of the 25 minutes
        val part = P.part(trip, after, pump, Long.MAX_VALUE)
        assertEquals(5.0, part.distanceKm, 0.1)
        assertEquals(pump, part.startMs)
        assertEquals(0.4, part.fuelL, 0.01)
        // The two sides add up to the drive.
        assertEquals(1.0, P.share(trip, s, 0, pump) + after, 1e-9)
        // The stop is found, in the middle of the drive.
        val stops = P.stops(s)
        assertEquals(1, stops.size)
        assertEquals(600_000L to 900_000L, stops[0])
        // Without a recording, or one that does not cover the drive (a merge across a gap), by time.
        assertEquals(13.0 / 25, P.share(trip, null, pump, Long.MAX_VALUE), 1e-9)
        assertEquals(13.0 / 25, P.share(trip.copy(distanceKm = 60.0), s, pump, Long.MAX_VALUE), 1e-9)
        // A drive wholly on one side.
        assertEquals(1.0, P.share(trip, s, 0, Long.MAX_VALUE), 1e-9)
        assertEquals(0.0, P.share(trip, s, 30 * m, Long.MAX_VALUE), 1e-9)
        // Added by hand with no time: on the side its start falls.
        val point = trip.copy(endMs = 0, manual = true)
        assertEquals(1.0, P.share(point, null, 0, pump), 1e-9)
        assertEquals(0.0, P.share(point, null, pump, Long.MAX_VALUE), 1e-9)
    }

    @Test
    fun theMomentsOfferedAreTheStopsAndDriveEndsBeforeTheFillUp() {
        val P = com.abeernch.civicobd.core.Portions
        val m = 60_000L
        val pts = mutableListOf<Pair<Long, Float>>()
        for (i in 0..600 step 2) pts += i * 1000L to 50f
        for (i in 900..1500 step 2) pts += i * 1000L to 40f
        val drive = com.abeernch.civicobd.core.TripRow(id = 1, startMs = 0, endMs = 25 * m, distanceKm = 15.0)
        val later = com.abeernch.civicobd.core.TripRow(id = 2, startMs = 120 * m, endMs = 140 * m, distanceKm = 9.0)
        val ms = P.moments(listOf(later to null, drive to speeds(*pts.toTypedArray())), afterMs = 0, beforeMs = 130 * m)
        // Newest first: the end of the first drive, then its stop at the pump. The later drive had not ended.
        assertEquals(2, ms.size)
        assertEquals(false, ms[0].duringDrive)
        assertEquals(25 * m + 30_000, ms[0].timeMs)
        assertEquals(true, ms[1].duringDrive)
        assertEquals(750_000L, ms[1].timeMs)
        assertEquals(300_000L, ms[1].stopMs)
        // Nothing before the fill-up before it.
        assertTrue(P.moments(listOf(drive to speeds(*pts.toTypedArray())), afterMs = 26 * m, beforeMs = 130 * m).isEmpty())
    }

    @Test
    fun theTankCountsOnlyThePartOfADriveAfterTheFillUp() {
        // Filled at 12 minutes into a 15 km drive: 5 km of it are in the new tank, with their fuel.
        val fill = com.abeernch.civicobd.core.Fillup(id = 1, timeMs = 12 * 60_000L, litres = 40.0, price = 350.0, full = true, odometer = null)
        val part = com.abeernch.civicobd.core.TripRow(id = 9, startMs = 12 * 60_000L, endMs = 25 * 60_000L, distanceKm = 5.0, fuelL = 0.4)
        val tank = com.abeernch.civicobd.core.tankOf(listOf(fill), listOf(part), 50.0, 0.0, 0.0, -1)
        assertEquals(0.4, tank.usedL, 1e-9)
        assertEquals(49.6, tank.leftL!!, 1e-9)
    }

    @Test
    fun newServiceJobsJoinASavedListWithoutLosingWhatWasLogged() {
        val M = com.abeernch.civicobd.core.Maintenance
        // Saved by an earlier version: its seven jobs, the oil logged with its own interval.
        val saved = listOf(
            com.abeernch.civicobd.core.ServiceItem("oil", "Engine oil and filter", 5_000.0, 6, doneKm = 195_000.0, doneMs = 1L),
            com.abeernch.civicobd.core.ServiceItem("tyres", "Tyre rotation", 8_000.0, 12),
        )
        val list = M.merged(saved)
        assertEquals(M.DEFAULTS.map { it.key }, list.map { it.key })
        assertEquals(195_000.0, list.first { it.key == "oil" }.doneKm!!, 1e-9)
        assertEquals(8_000.0, list.first { it.key == "tyres" }.everyKm!!, 1e-9)
        assertTrue(list.any { it.key == "timing" && it.name == "Timing belt" })
        // The battery goes by time only: never logged, nothing is due; logged, it is due by months.
        val battery = list.first { it.key == "battery" }
        assertEquals(null, battery.everyKm)
        assertEquals(false, M.status(battery, 199_872.0).known)
        assertEquals(36 * 30 - 1, M.status(battery.copy(doneMs = 0L), 199_872.0, now = 86_400_000L).dueInDays)
        // A job not in the list is kept, at the end.
        val custom = com.abeernch.civicobd.core.ServiceItem("hoses", "Radiator hoses", 60_000.0, 48)
        assertEquals("hoses", M.merged(saved + custom).last().key)
    }
}
