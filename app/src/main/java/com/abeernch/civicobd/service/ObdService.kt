package com.abeernch.civicobd.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.abeernch.civicobd.MainActivity
import com.abeernch.civicobd.R
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.Derived
import com.abeernch.civicobd.core.DiagRequest
import com.abeernch.civicobd.core.Diagnostics
import com.abeernch.civicobd.core.Events
import com.abeernch.civicobd.core.Link
import com.abeernch.civicobd.core.LiveBus
import com.abeernch.civicobd.core.MetricEngine
import com.abeernch.civicobd.core.Monitor
import com.abeernch.civicobd.core.Monitors
import com.abeernch.civicobd.core.TripRow
import com.abeernch.civicobd.core.TripStore
import com.abeernch.civicobd.core.TripTotals
import com.abeernch.civicobd.obd.ClassicTransport
import com.abeernch.civicobd.obd.ElmSession
import com.abeernch.civicobd.obd.Pids
import com.abeernch.civicobd.core.TripSummary
import com.abeernch.civicobd.obd.SimTransport
import com.abeernch.civicobd.obd.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Runs in the background with a small permanent notification.
 * Keeps Bluetooth on, connects to the adapter whenever it is in range,
 * records trips automatically and publishes live values to the screens.
 */
@SuppressLint("MissingPermission") // MainActivity obtains Bluetooth permission before starting this.
class ObdService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: AppSettings
    private lateinit var store: TripStore
    private lateinit var metrics: MetricEngine

    private var trip = TripTotals()
    private var lastRunningMs = 0L
    private var lastSavedMs = 0L
    private var lastNoteMs = 0L
    private var wasLive = false

    private lateinit var recordings: com.abeernch.civicobd.core.Recordings
    private lateinit var events: Events
    private lateinit var route: RouteTracker
    private var recId = -1L
    private var lastSampleMs = 0L
    private val pending = ArrayList<com.abeernch.civicobd.core.Sample>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        store = TripStore.get(this)
        recordings = com.abeernch.civicobd.core.Recordings(store)
        events = Events(store)
        route = RouteTracker(this)
        metrics = MetricEngine(settings.gearRatios.split(",").mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 5 })
        createChannel()
        Alerts.createChannels(this)
        startForeground(NOTE_ID, note("Waiting for the car"))
        scope.launch { mainLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        closeTrip(System.currentTimeMillis())
        scope.cancel()
        super.onDestroy()
    }

    // ---- Connection loop ----

    private suspend fun mainLoop() {
        val bt = getSystemService(BluetoothManager::class.java)?.adapter
        while (true) {
            fillFromGps(System.currentTimeMillis())
            checkTripEnd(System.currentTimeMillis())

            if (settings.demo) {
                wasLive = false
                try {
                    session(SimTransport(), "demo car")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LiveBus.log("Demo: ${e.message}")
                }
                delay(1_000)
                continue
            }

            if (bt == null) {
                setLink(Link.BluetoothOff, "This phone has no Bluetooth")
                delay(60_000)
                continue
            }
            if (!bt.isEnabled) {
                setLink(Link.BluetoothOff, "Turning Bluetooth on")
                try {
                    @Suppress("DEPRECATION")
                    bt.enable()
                } catch (e: SecurityException) {
                    LiveBus.log("Bluetooth enable refused: ${e.message}")
                }
                delay(5_000)
                continue
            }

            val device = pickAdapter(bt)
            if (device == null) {
                setLink(Link.NoAdapter, "Pair the OBD adapter in Bluetooth settings")
                delay(20_000)
                continue
            }

            wasLive = false
            try {
                session(ClassicTransport(device), device.name ?: "adapter")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LiveBus.log("Link: ${e.message}")
            }

            setLink(Link.Searching, "Looking for the adapter")
            // Retry fast after a drop while driving, slowly while parked.
            delay(if (wasLive) 2_000 else 20_000)
        }
    }

    /** The saved adapter, or the first paired device whose name looks like an OBD adapter. */
    private fun pickAdapter(bt: BluetoothAdapter): BluetoothDevice? {
        val saved = settings.adapterAddress
        val bonded = bt.bondedDevices ?: emptySet()
        bonded.firstOrNull { it.address == saved }?.let { return it }
        val guess = bonded.firstOrNull { d ->
            val n = (d.name ?: "").uppercase()
            listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "KONNWEI").any { n.contains(it) }
        }
        if (guess != null) {
            settings.adapterAddress = guess.address
            settings.adapterName = guess.name
        }
        return guess
    }

    /** Talks to one adapter until the link drops, or until demo mode is switched on or off. */
    private suspend fun session(link: Transport, name: String) {
        val demo = settings.demo
        setLink(Link.Connecting, "Connecting to $name")
        link.connect()
        try {
            val elm = ElmSession(link) { LiveBus.log(it) }
            initAdapter(elm)
            var misses = 0
            while (settings.demo == demo) {
                // Wake the engine computer. It stays silent while the ignition is off.
                val first = elm.send("0100", timeoutMs = 12_000)
                // Only a real answer counts ("41 00" and the supported-PID bits), never a stray reply.
                if (!first.ok || Pids.supported(first, 0x00).isEmpty()) {
                    misses++
                    fillFromGps(System.currentTimeMillis())
                    checkTripEnd(System.currentTimeMillis())
                    val volts = readVoltage(elm) // also proves the link is still alive
                    // Above about 13.3 V the alternator is charging, so the engine is running.
                    val engineRunning = volts != null && volts >= 13.3
                    setLink(
                        Link.EngineOff,
                        if (engineRunning) "Engine on. Reaching the engine computer" else "Connected. Waiting for ignition",
                    )
                    // After the car has been off, the adapter can believe its K-line link is still open and
                    // answer NO DATA at once without trying. Closing the protocol makes the next request
                    // start again with the slow ISO 9141-2 handshake.
                    elm.send("ATPC")
                    if (engineRunning && misses % 3 == 0) {
                        // Engine clearly on but still silent: reset the adapter. Every second time,
                        // let it search for the protocol itself in case the saved one is wrong.
                        val search = misses % 6 == 0
                        LiveBus.log("Engine running (%.1f V) but no answer: resetting the adapter%s"
                            .format(volts, if (search) " and searching for the protocol" else ""))
                        initAdapter(elm, search)
                    }
                    delay(if (engineRunning) 2_000 else 8_000)
                    continue
                }
                misses = 0
                val ready = afterWake(elm, first)
                poll(elm, ready.first, ready.second)
            }
        } finally {
            link.close()
        }
    }

    private suspend fun initAdapter(elm: ElmSession, search: Boolean = false) {
        elm.send("ATZ", timeoutMs = 3000)
        delay(300)
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) elm.send(cmd)
        val version = elm.send("ATI").text
        val saved = settings.protocol
        // "ATSPA3" tries the saved protocol first and searches if it fails; "ATSP3" sticks to it.
        elm.send(
            when {
                saved == null -> "ATSP0"
                search -> "ATSPA$saved"
                else -> "ATSP$saved"
            }
        )
        LiveBus.update { it.copy(adapter = version) }
    }

    /** Reads what the car supports and tunes polling speed. Returns supported PIDs and the request suffix. */
    private suspend fun afterWake(elm: ElmSession, first: com.abeernch.civicobd.obd.ElmReply): Pair<List<Int>, String> {
        val number = elm.send("ATDPN").text.removePrefix("A").trim()
        if (number.length == 1) settings.protocol = number

        val supported = mutableListOf<Int>()
        var block = Pids.supported(first, 0x00)
        supported += block
        var base = 0x20
        while (base <= 0xA0 && block.contains(base)) {
            block = Pids.supported(elm.send(Pids.command(base), timeoutMs = 3000), base)
            supported += block
            base += 0x20
        }

        // "1" after a request tells the adapter one answer is enough. Cuts the wait on ISO 9141-2.
        val trial = elm.send(Pids.command(0x0C) + "1", timeoutMs = 1500)
        val suffix = if (Pids.dataBytes(trial, 0x0C, 2) != null) "1" else ""
        elm.send("ATAT2")

        LiveBus.update {
            it.copy(
                protocol = "ISO 9141-2".takeIf { number == "3" } ?: "Protocol $number",
                supported = supported.filter { code -> code % 0x20 != 0 },
            )
        }
        readMonitors(elm, suffix)
        readDiagnostics(elm, suffix)
        return supported to suffix
    }

    // ---- Polling ----

    private suspend fun poll(elm: ElmSession, supported: List<Int>, suffix: String) {
        wasLive = true
        setLink(Link.Live, if (settings.demo) "Demo drive" else "Live")
        var cycle = 0
        var fails = 0
        var lastSlowMs = 0L
        var lastMonitorsMs = System.currentTimeMillis()

        fun has(code: Int) = supported.isEmpty() || code in supported

        val demo = settings.demo
        while (settings.demo == demo) {
            yield()
            val start = System.currentTimeMillis()

            // First tier, every cycle: engine speed, road speed and intake pressure, which give
            // instant economy, gear and the shift light. Then one more reading per cycle in turn:
            // throttle every second cycle (needed to spot fuel cut), and the rest, which change slowly
            // or matter less (intake air, coolant, load, timing), once in eight cycles each.
            // Over the slow K-line each request costs about 0.2 s, so this keeps cycles short.
            val codes = mutableListOf(0x0C, 0x0D, 0x0B)
            codes += EXTRA_ROTATION[cycle % EXTRA_ROTATION.size]
            val slow = start - lastSlowMs > 5_000

            val values = HashMap(LiveBus.state.value.values)
            var answered = 0
            for (code in codes) {
                if (!has(code)) continue
                val pid = Pids.BY_CODE[code] ?: continue
                val value = Pids.decode(elm.send(Pids.command(code) + suffix, timeoutMs = 1500), pid)
                if (value != null) {
                    values[code] = value
                    answered++
                }
            }

            if (answered == 0) {
                fillFromGps(System.currentTimeMillis())
                fails++
                if (fails >= 3) {
                    LiveBus.log("Engine computer stopped answering")
                    values.remove(0x0C)
                    values.remove(0x0D)
                    LiveBus.update { it.copy(values = values) }
                    return
                }
                continue
            }
            fails = 0
            // The engine computer answered, so the ignition is on (engine running or not).
            lastIgnitionMs = System.currentTimeMillis()

            if (slow) {
                lastSlowMs = start
                readVoltage(elm)
            }
            if (start - lastMonitorsMs > 30_000) {
                lastMonitorsMs = start
                readMonitors(elm, suffix)
            }
            // Fault-code requests from the screens run between polling cycles.
            Diagnostics.requests.tryReceive().getOrNull()?.let { runDiagnostics(elm, it, suffix) }

            val now = System.currentTimeMillis()
            watchStart(elm, values[0x0C] ?: 0.0, now)
            val derived = metrics.step(values, now, settings.fuelFactor)
            recordTrip(values, derived, now)

            LiveBus.update {
                it.copy(values = values, derived = derived, trip = trip, cycleMs = now - start)
            }
            maybeUpdateNote(derived, now)
            cycle++
        }
    }

    // ---- Fault codes ----

    private suspend fun runDiagnostics(elm: ElmSession, request: DiagRequest, suffix: String) {
        if (request == DiagRequest.Clear) {
            val reply = elm.send("04", timeoutMs = 5000)
            val ok = reply.lines.any { it.startsWith("44") }
            LiveBus.log(if (ok) "Fault codes cleared" else "Clear failed: ${reply.error ?: reply.text}")
            events.add("dtc_clear", null, null, if (ok) "cleared" else "failed")
            readDiagnostics(elm, suffix)
            Diagnostics.publish { it.copy(message = if (ok) "Codes cleared. The check-engine light goes off now." else "The car refused to clear the codes.") }
            readMonitors(elm, suffix)
            return
        }
        readDiagnostics(elm, suffix)
    }

    /** Stored and pending codes, plus the freeze frame when a code is stored. */
    private suspend fun readDiagnostics(elm: ElmSession, suffix: String) {
        val stored = elm.send("03", timeoutMs = 4000).let { if (it.error == "NO DATA") emptyList() else Diagnostics.parseCodes(it.lines, 3) }
        val pending = elm.send("07", timeoutMs = 4000).let { if (it.error == "NO DATA") emptyList() else Diagnostics.parseCodes(it.lines, 7) }
        var frame = emptyMap<Int, Double>()
        var frameCode: String? = null
        if (stored.isNotEmpty()) {
            frameCode = Pids.dataBytes(elm.send("020200", timeoutMs = 2000).let {
                // Mode 02 replies start with 42 and repeat the PID and frame number: 42 02 00 AA BB
                it.copy(lines = it.lines.map { l -> l.replace(" ", "").replaceFirst("420200", "4102") })
            }, 0x02, 2)?.let { b -> Diagnostics.decode("%02X%02X".format(b[0], b[1]))?.code }
            val values = HashMap<Int, Double>()
            for (code in listOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11)) {
                val pid = Pids.BY_CODE[code] ?: continue
                val reply = elm.send("02%02X00".format(code), timeoutMs = 1500)
                val asMode1 = reply.copy(lines = reply.lines.map { l ->
                    l.replace(" ", "").replaceFirst("42%02X00".format(code), "41%02X".format(code))
                })
                Pids.decode(asMode1, pid)?.let { values[code] = it }
            }
            frame = values
        }
        val known = Diagnostics.state.value.stored.map { it.code }.toSet()
        stored.filter { it.code !in known }.forEach { events.add("dtc", null, null, it.code) }
        Diagnostics.publish {
            it.copy(
                stored = stored, pending = pending, freezeFrame = frame, freezeCode = frameCode,
                readAtMs = System.currentTimeMillis(), busy = false,
                message = if (stored.isEmpty() && pending.isEmpty()) "No fault codes." else it.message,
            )
        }
        LiveBus.log("Fault codes: stored ${stored.size}, pending ${pending.size}")
        if (!settings.demo) {
            try { Alerts.codes(this, settings, stored, pending, LiveBus.state.value.monitors?.mil) } catch (e: Exception) { LiveBus.log("Alert failed: ${e.message}") }
        }
    }

    private suspend fun readVoltage(elm: ElmSession): Double? {
        val text = elm.send("ATRV").text
        val volts = text.removeSuffix("V").trim().toDoubleOrNull()
        if (volts != null) LiveBus.update { it.copy(voltage = volts) }
        return volts
    }

    // ---- Battery at engine start ----
    // With the ignition on and the engine off, the voltage is sampled every cycle. When the engine
    // starts, the resting voltage (before cranking) and the lowest voltage while cranking are saved.
    private val voltHistory = ArrayDeque<Pair<Long, Double>>()
    private var engineWasOff = false

    private suspend fun watchStart(elm: ElmSession, rpm: Double, now: Long) {
        if (rpm < 1) {
            readVoltage(elm)?.let { voltHistory.addLast(now to it) }
            while (voltHistory.isNotEmpty() && now - voltHistory.first().first > 10_000) voltHistory.removeFirst()
            engineWasOff = true
            return
        }
        if (engineWasOff && voltHistory.isNotEmpty()) {
            val before = voltHistory.filter { now - it.first > 3_000 }.map { it.second }
            val rest = (before.ifEmpty { voltHistory.map { it.second } }).maxOrNull()
            val crank = voltHistory.filter { now - it.first <= 4_000 }.minOfOrNull { it.second }
            events.add("start", rest, crank?.takeIf { rest == null || it < rest - 0.3 }, null)
            LiveBus.log("Engine start: rest ${rest ?: "--"} V, crank ${crank ?: "--"} V")
            LiveBus.tripsChanged()
        }
        voltHistory.clear()
        engineWasOff = false
    }

    /** Mode 01 PID 01: check-engine light, fault code count and readiness monitors. */
    private suspend fun readMonitors(elm: ElmSession, suffix: String) {
        val bytes = Pids.dataBytes(elm.send("0101$suffix", timeoutMs = 1500), 0x01, 4) ?: return
        val a = bytes[0]
        val b = bytes[1]
        val c = bytes[2]
        val d = bytes[3]
        fun bit(x: Int, n: Int) = (x shr n) and 1 == 1
        val list = mutableListOf(
            Monitor("Misfire", bit(b, 0), !bit(b, 4)),
            Monitor("Fuel system", bit(b, 1), !bit(b, 5)),
            Monitor("Components", bit(b, 2), !bit(b, 6)),
        )
        val names = listOf(
            "Catalyst", "Heated catalyst", "Evaporative system", "Secondary air",
            "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR system",
        )
        names.forEachIndexed { i, name -> list += Monitor(name, bit(c, i), !bit(d, i)) }
        LiveBus.update { it.copy(monitors = Monitors(bit(a, 7), a and 0x7F, list)) }
        // A check-engine light that comes on mid-drive is announced straight away.
        if (!settings.demo && bit(a, 7) && !settings.milWasOn) {
            val d = Diagnostics.state.value
            try { Alerts.codes(this, settings, d.stored, d.pending, true) } catch (e: Exception) { LiveBus.log("Alert failed: ${e.message}") }
        }
    }

    // ---- Trips ----

    private fun recordTrip(values: Map<Int, Double>, d: Derived, now: Long) {
        val rpm = values[0x0C] ?: 0.0
        val speed = values[0x0D] ?: 0.0
        if (rpm > 0) {
            lastRunningMs = now
            if (!trip.active) openTrip(now) else resumeIfPaused()
            // Never count time twice: after a gap filled from GPS, only the time since then is added.
            val dt = if (lastTickMs > 0) minOf(d.dtS, (now - lastTickMs) / 1000.0).coerceAtLeast(0.0) else d.dtS
            lastTickMs = now
            trip = trip.copy(
                distanceKm = trip.distanceKm + speed * dt / 3600.0,
                fuelL = trip.fuelL + (d.fuelLph ?: 0.0) * dt / 3600.0,
                driveS = trip.driveS + if (speed >= 1) dt else 0.0,
                idleS = trip.idleS + if (speed < 1) dt else 0.0,
                maxSpeed = maxOf(trip.maxSpeed, speed),
            )
            recordSamples(values, d, now)
            if (now - lastSavedMs > 10_000) saveTrip(now)
        }
        checkTripEnd(now)
    }

    private fun openTrip(now: Long) {
        // A drive that stopped less than 10 minutes ago, and was not ended by hand, carries on:
        // the same trip, recording and route. This also covers the app being stopped while parked.
        val last = store.between(0, Long.MAX_VALUE).firstOrNull()
        val lastRec = last?.let { recordings.forTrip(it.id) }
        if (last != null && lastRec != null && lastRec.source == "Live" && last.id != settings.endedTripId &&
            now >= last.endMs && now - last.endMs < CONTINUE_MS
        ) {
            trip = TripTotals(
                id = last.id, startMs = last.startMs, distanceKm = last.distanceKm, fuelL = last.fuelL,
                driveS = last.driveS, idleS = last.idleS, maxSpeed = last.maxSpeed,
            )
            recId = lastRec.id
            LiveBus.log("Drive continues after %d s stopped".format((now - last.endMs) / 1000))
            Alerts.cancelTrip(this)
        } else {
            val id = store.insert(TripRow(startMs = now, endMs = now))
            trip = TripTotals(id = id, startMs = now)
            recId = recordings.create(now, "Live", "Drive", id)
            LiveBus.clearTrail()
            LiveBus.log("Trip started")
        }
        if (!settings.demo) route.start()
        lastSavedMs = now
        LiveBus.tripsChanged()
    }

    /** The engine started again during a stop: same drive, summary withdrawn until the next stop. */
    private fun resumeIfPaused() {
        if (!summaryShown) return
        summaryShown = false
        LiveBus.update { it.copy(tripPaused = false) }
        Alerts.cancelTrip(this)
        if (!settings.demo) route.start()
        LiveBus.log("Drive continues")
    }

    // ---- 1 Hz recording, with Car Scanner's parameter names so both apps chart alike ----

    private fun recordSamples(values: Map<Int, Double>, d: Derived, now: Long) {
        // Every polling cycle is recorded (about 1.5 per second), at most one sample per 0.4 s.
        if (recId < 0 || now - lastSampleMs < 400) return
        val sinceLastS = if (lastSampleMs == 0L) 1.0 else ((now - lastSampleMs) / 1000.0).coerceAtMost(5.0)
        lastSampleMs = now
        fun add(name: String, unit: String, value: Double?) {
            if (value != null && !value.isNaN()) pending += com.abeernch.civicobd.core.Sample(now, name, unit, value)
        }
        add("Vehicle speed", "km/h", values[0x0D])
        add("Engine RPM", "rpm", values[0x0C])
        add("Intake manifold absolute pressure", "kPa", values[0x0B])
        add("Throttle position", "%", values[0x11])
        add("Intake air temperature", "°C", values[0x0F])
        add("Calculated engine load value", "%", values[0x04])
        add("Timing advance", "°", values[0x0E])
        add("Engine coolant temperature", "°C", values[0x05])
        add("OBD Module Voltage", "V", LiveBus.state.value.voltage)
        add("Calculated instant fuel rate", "L/h", d.fuelLph)
        add("Calculated instant fuel consumption", "km/L", d.instantKmL)
        add("Average fuel consumption 10 sec", "km/L", d.avg10KmL)
        add("Average fuel consumption", "km/L", trip.kmPerL)
        add("Average speed", "km/h", trip.avgSpeed)
        add("Distance travelled", "km", trip.distanceKm)
        add("Fuel used", "L", trip.fuelL)
        add("Fuel used price", "pkr", trip.fuelL * settings.fuelPrice)
        add("Vehicle acceleration", "g", d.accelG)
        add("Instant engine power (based on fuel consumption)", "hp", d.powerHp)
        add("Calculated boost", "psi", d.boostKpa?.let { it * 0.145038 })
        add("Gear", "", d.gear.toDouble())
        if (settings.demo) route.demoStep(values[0x0D] ?: 0.0, sinceLastS)
        route.latest?.let { fix ->
            add("Latitude", "°", fix.lat)
            add("Longitude", "°", fix.lon)
            add("Speed (GPS)", "km/h", fix.speedKmh)
            add("Altitude (GPS)", "m", fix.altitudeM)
            add("GPS accuracy", "m", fix.accuracyM)
            publishPosition(fix, d)
        }
        if (pending.size >= 100) flushSamples(now)
    }

    // Last place the direction of travel was measured from. Bearing only updates after
    // the car has moved a few metres, so the arrow does not spin while standing still.
    private var headingFrom: com.abeernch.civicobd.service.Fix? = null
    private var bearing: Double? = null

    /** Sends the fix and a trail point to the live map on the Drive screen. */
    private fun publishPosition(fix: com.abeernch.civicobd.service.Fix, d: Derived) {
        val from = headingFrom
        if (from == null) {
            headingFrom = fix
        } else if (com.abeernch.civicobd.core.Route.metres(from.lat, from.lon, fix.lat, fix.lon) > 4.0) {
            bearing = com.abeernch.civicobd.core.Route.bearing(from.lat, from.lon, fix.lat, fix.lon)
            headingFrom = fix
        }
        val pos = com.abeernch.civicobd.core.LivePosition(fix.lat, fix.lon, bearing, fix.accuracyM)
        LiveBus.update { it.copy(position = pos) }
        val speed = LiveBus.state.value.values[0x0D] ?: 0.0
        LiveBus.addTrail(
            com.abeernch.civicobd.core.TrailPoint(
                fix.lat, fix.lon, d.instantKmL,
                fuelCut = d.fuelCut || ((d.fuelLph ?: 1.0) < 0.05 && speed > 5),
            )
        )
    }

    // Last time the trip totals moved forward, from engine data or from GPS.
    private var lastTickMs = 0L

    /**
     * While the engine computer is silent in the middle of a trip, keeps distance, time and the
     * route going from GPS. A car still moving also keeps the trip open, so it is not ended early.
     */
    private fun fillFromGps(now: Long) {
        // Paused (parked, ignition off): GPS is off and nothing is filled in, so walking away
        // from the car is never recorded.
        if (!trip.active || settings.demo || summaryShown) return
        val fix = route.latest ?: return
        if (now - fix.atMs > 5_000) return
        val kmh = fix.speedKmh ?: return
        val dt = if (lastTickMs > 0) ((now - lastTickMs) / 1000.0).coerceIn(0.0, 30.0) else 0.0
        lastTickMs = now
        // Only car speed counts. Below it the phone may be in a pocket walking away from the car
        // (5 to 6 km/h was once taken for driving and kept a parked drive going), or the car is
        // standing with the engine off; neither adds distance or keeps the drive open.
        if (kmh < CAR_SPEED_KMH) return
        lastRunningMs = now
        trip = trip.copy(
            distanceKm = trip.distanceKm + kmh * dt / 3600.0,
            driveS = trip.driveS + dt,
            maxSpeed = maxOf(trip.maxSpeed, kmh),
        )
        if (recId >= 0 && now - lastSampleMs >= 1_000) {
            lastSampleMs = now
            fun add(name: String, unit: String, value: Double?) {
                if (value != null && !value.isNaN()) pending += com.abeernch.civicobd.core.Sample(now, name, unit, value)
            }
            add("Latitude", "°", fix.lat)
            add("Longitude", "°", fix.lon)
            add("Speed (GPS)", "km/h", fix.speedKmh)
            add("Altitude (GPS)", "m", fix.altitudeM)
            add("GPS accuracy", "m", fix.accuracyM)
            add("Distance travelled", "km", trip.distanceKm)
            publishPosition(fix, Derived())
        }
        LiveBus.update { it.copy(trip = trip) }
        if (now - lastSavedMs > 10_000) saveTrip(now)
    }

    private fun flushSamples(now: Long) {
        if (recId < 0) return
        recordings.add(recId, pending.toList())
        pending.clear()
        recordings.setEnd(recId, now)
    }

    private fun saveTrip(endMs: Long) {
        if (!trip.active) return
        store.update(
            TripRow(
                id = trip.id, startMs = trip.startMs, endMs = endMs,
                distanceKm = trip.distanceKm, fuelL = trip.fuelL,
                cost = trip.fuelL * settings.fuelPrice,
                driveS = trip.driveS, idleS = trip.idleS, maxSpeed = trip.maxSpeed,
            )
        )
        lastSavedMs = System.currentTimeMillis()
        LiveBus.tripsChanged()
        refreshWidget(force = false)
    }

    // Last time the engine computer answered at all, engine running or not.
    private var lastIgnitionMs = 0L

    // A summary has been shown for the current stop; cleared when the engine runs again.
    private var summaryShown = false

    /**
     * After 60 s parked (ignition off, or adapter unplugged, and by GPS not moving) the drive is paused and its
     * summary shown. It continues if the engine starts within 10 minutes; after that, or when the
     * user taps "End drive now", it is closed and the next start is a new drive.
     */
    private fun checkTripEnd(now: Long) {
        if (!trip.active) return
        if (LiveBus.takeEndRequest()) {
            settings.endedTripId = trip.id
            closeTrip(lastRunningMs.coerceAtLeast(trip.startMs))
            return
        }
        // A stop counts from the later of: the engine last running, or the ignition last on.
        // With the ignition on and the engine off (waiting at a signal), the engine computer keeps
        // answering, so the drive is neither paused nor ended. Only once it falls silent (ignition
        // off or adapter unplugged) does the one-minute pause and ten-minute end apply.
        val parkedFor = now - maxOf(lastRunningMs, lastIgnitionMs)
        when {
            parkedFor > CONTINUE_MS -> closeTrip(lastRunningMs)
            parkedFor > PAUSE_MS && !summaryShown -> pauseTrip()
        }
    }

    private fun pauseTrip() {
        summaryShown = true
        saveTrip(lastRunningMs)
        flushSamples(lastRunningMs)
        LiveBus.update { it.copy(tripPaused = true) }
        route.stop() // no tracking while parked; it restarts with the engine
        LiveBus.log("Parked. The drive continues if the engine starts within 10 minutes")
        if (trip.distanceKm >= 0.1) showSummary(paused = true)
    }

    private fun showSummary(paused: Boolean) {
        try {
            store.byId(trip.id)?.let { row -> Alerts.tripEnded(this, TripSummary.of(row, store), paused) }
            Alerts.service(this, settings, com.abeernch.civicobd.core.Car.status(settings, store, 0.0).odometerKm)
        } catch (e: Exception) {
            LiveBus.log("Summary failed: ${e.message}")
        }
    }

    private var lastWidgetMs = 0L

    /** Pushes fresh numbers to the home-screen widget, at most once a minute while driving. */
    private fun refreshWidget(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWidgetMs < 60_000) return
        lastWidgetMs = now
        scope.launch { com.abeernch.civicobd.widget.TripWidget.refresh(this@ObdService) }
    }

    private fun closeTrip(endMs: Long) {
        if (!trip.active) return
        saveTrip(endMs)
        flushSamples(endMs)
        settings.gearRatios = metrics.ratios().joinToString(",") { "%.3f".format(java.util.Locale.US, it) }
        if (trip.distanceKm < 0.1) {
            // Engine started but the car did not move: keep nothing.
            if (recId >= 0) recordings.delete(recId, withTrip = false)
            store.delete(trip.id)
        } else if (!summaryShown) {
            // Ended before the one-minute pause (for example by hand): the summary now.
            showSummary(paused = false)
        }
        summaryShown = false
        LiveBus.update { it.copy(tripPaused = false) }
        recId = -1
        lastTickMs = 0L
        route.stop()
        headingFrom = null
        bearing = null
        LiveBus.clearTrail()
        LiveBus.update { it.copy(position = null) }
        LiveBus.log("Trip ended: %.1f km".format(trip.distanceKm))
        trip = TripTotals()
        LiveBus.update { it.copy(trip = trip) }
        LiveBus.tripsChanged()
        refreshWidget(force = true)
    }

    // ---- Status and notification ----

    private fun setLink(link: Link, status: String) {
        LiveBus.update { it.copy(link = link, status = status) }
        val text = if (link == Link.Live) "Live" else status
        notify(text)
    }

    private fun maybeUpdateNote(d: Derived, now: Long) {
        if (now - lastNoteMs < 5_000) return
        lastNoteMs = now
        val avg = trip.kmPerL?.let { "%.1f km/L".format(it) } ?: "--"
        notify("Trip %.1f km  ·  %s".format(trip.distanceKm, avg))
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, note(text))
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Car connection", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("Civic OBD")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        /** GPS speed that can only be the car moving, never someone walking or jogging. */
        private const val CAR_SPEED_KMH = 15.0

        /** Parked this long, the drive pauses and its summary is shown. */
        private const val PAUSE_MS = 60_000L
        /** Parked longer than this, the drive ends; a start after it begins a new drive. */
        private const val CONTINUE_MS = 10 * 60_000L

        /** The one extra reading taken in each polling cycle, in turn. */
        private val EXTRA_ROTATION = listOf(0x11, 0x0F, 0x11, 0x05, 0x11, 0x04, 0x11, 0x0E)

        private const val CHANNEL = "car"
        private const val NOTE_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java))
        }
    }
}
