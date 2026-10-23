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
    private val metrics = MetricEngine()

    private var trip = TripTotals()
    private var lastRunningMs = 0L
    private var lastSavedMs = 0L
    private var lastNoteMs = 0L
    private var wasLive = false

    private lateinit var recordings: com.abeernch.civicobd.core.Recordings
    private var recId = -1L
    private var lastSampleMs = 0L
    private val pending = ArrayList<com.abeernch.civicobd.core.Sample>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        store = TripStore.get(this)
        recordings = com.abeernch.civicobd.core.Recordings(store)
        createChannel()
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
            while (settings.demo == demo) {
                // Wake the engine computer. It stays silent while the ignition is off.
                val first = elm.send("0100", timeoutMs = 12_000)
                if (!first.ok) {
                    setLink(Link.EngineOff, "Connected. Waiting for ignition")
                    checkTripEnd(System.currentTimeMillis())
                    delay(8_000)
                    readVoltage(elm) // also proves the link is still alive
                    continue
                }
                val ready = afterWake(elm, first)
                poll(elm, ready.first, ready.second)
            }
        } finally {
            link.close()
        }
    }

    private suspend fun initAdapter(elm: ElmSession) {
        elm.send("ATZ", timeoutMs = 3000)
        delay(300)
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) elm.send(cmd)
        val version = elm.send("ATI").text
        val saved = settings.protocol
        elm.send(if (saved != null) "ATSP$saved" else "ATSP0")
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

            // Fast tier every cycle: everything the fuel model needs.
            val codes = mutableListOf(0x0C, 0x0D, 0x0B, 0x11)
            if (cycle % 3 == 0) codes += listOf(0x0F, 0x04, 0x0E)
            val slow = start - lastSlowMs > 5_000
            if (slow) codes += 0x05

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

            if (slow) {
                lastSlowMs = start
                readVoltage(elm)
            }
            if (start - lastMonitorsMs > 30_000) {
                lastMonitorsMs = start
                readMonitors(elm, suffix)
            }

            val now = System.currentTimeMillis()
            val derived = metrics.step(values, now, settings.fuelFactor)
            recordTrip(values, derived, now)

            LiveBus.update {
                it.copy(values = values, derived = derived, trip = trip, cycleMs = now - start)
            }
            maybeUpdateNote(derived, now)
            cycle++
        }
    }

    private suspend fun readVoltage(elm: ElmSession) {
        val text = elm.send("ATRV").text
        val volts = text.removeSuffix("V").trim().toDoubleOrNull()
        if (volts != null) LiveBus.update { it.copy(voltage = volts) }
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
    }

    // ---- Trips ----

    private fun recordTrip(values: Map<Int, Double>, d: Derived, now: Long) {
        val rpm = values[0x0C] ?: 0.0
        val speed = values[0x0D] ?: 0.0
        if (rpm > 0) {
            lastRunningMs = now
            if (!trip.active) openTrip(now)
            val dt = d.dtS
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
        val id = store.insert(TripRow(startMs = now, endMs = now))
        trip = TripTotals(id = id, startMs = now)
        recId = recordings.create(now, "Live", "Drive", id)
        lastSavedMs = now
        LiveBus.log("Trip started")
        LiveBus.tripsChanged()
    }

    // ---- 1 Hz recording, with Car Scanner's parameter names so both apps chart alike ----

    private fun recordSamples(values: Map<Int, Double>, d: Derived, now: Long) {
        if (recId < 0 || now - lastSampleMs < 1_000) return
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
        if (pending.size >= 100) flushSamples(now)
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
    }

    /** Ends the trip after 90 s without the engine running, whether the link is up or not. */
    private fun checkTripEnd(now: Long) {
        if (trip.active && now - lastRunningMs > 90_000) closeTrip(lastRunningMs)
    }

    private fun closeTrip(endMs: Long) {
        if (!trip.active) return
        saveTrip(endMs)
        flushSamples(endMs)
        if (trip.distanceKm < 0.1) {
            // Engine started but the car did not move: keep nothing.
            if (recId >= 0) recordings.delete(recId, withTrip = false)
            store.delete(trip.id)
        }
        recId = -1
        LiveBus.log("Trip ended: %.1f km".format(trip.distanceKm))
        trip = TripTotals()
        LiveBus.update { it.copy(trip = trip) }
        LiveBus.tripsChanged()
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
        private const val CHANNEL = "car"
        private const val NOTE_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java))
        }
    }
}
