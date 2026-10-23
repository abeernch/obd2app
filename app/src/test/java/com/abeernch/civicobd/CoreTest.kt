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

    private fun createTempDir(): File = File.createTempFile("civic", "").let {
        it.delete()
        it.mkdirs()
        it
    }
}
