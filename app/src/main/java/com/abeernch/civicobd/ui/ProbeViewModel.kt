package com.abeernch.civicobd.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abeernch.civicobd.obd.ClassicTransport
import com.abeernch.civicobd.obd.ElmSession
import com.abeernch.civicobd.obd.Pids
import com.abeernch.civicobd.obd.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { Idle, Connecting, Initialising, Live, Failed }

data class DeviceItem(val name: String, val address: String)

data class ProbeState(
    val devices: List<DeviceItem> = emptyList(),
    val selected: String? = null,
    val phase: Phase = Phase.Idle,
    val message: String = "Choose your adapter",
    val version: String = "",
    val protocol: String = "",
    val voltage: String = "",
    val supported: List<Int> = emptyList(),
    val live: Map<Int, Double> = emptyMap(),
    val cycleMs: Long = 0,
    val log: List<String> = emptyList(),
)

/**
 * Phase 1 connection probe. Connects, runs the init sequence from the spec,
 * reports what the adapter and car support, then polls a few live values.
 */
@SuppressLint("MissingPermission") // MainActivity checks Bluetooth permission first.
class ProbeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ProbeState())
    val state: StateFlow<ProbeState> = _state

    private var job: Job? = null
    private var transport: Transport? = null
    private var reachedLive = false
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Values polled in the live loop, in priority order.
    private val livePids = listOf(0x0C, 0x0D, 0x11, 0x0B, 0x0F, 0x05, 0x04, 0x06, 0x07, 0x0E, 0x10)

    private fun bluetoothAdapter() =
        getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter

    fun loadDevices() {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            _state.update { it.copy(message = "This phone has no Bluetooth") }
            return
        }
        if (!adapter.isEnabled) {
            _state.update { it.copy(message = "Turn on Bluetooth, then reopen the app") }
            return
        }
        val items = adapter.bondedDevices
            .map { DeviceItem(it.name ?: "Unnamed device", it.address) }
            .sortedByDescending { looksLikeObd(it.name) }
        _state.update { s ->
            s.copy(
                devices = items,
                selected = s.selected ?: items.firstOrNull { looksLikeObd(it.name) }?.address,
                message = if (items.isEmpty()) "Pair the adapter in Bluetooth settings first" else s.message,
            )
        }
    }

    private fun looksLikeObd(name: String): Boolean {
        val n = name.uppercase()
        return listOf("OBD", "ELM", "V-LINK", "VLINK", "CAR").any { n.contains(it) }
    }

    fun select(address: String) {
        if (_state.value.phase == Phase.Idle || _state.value.phase == Phase.Failed) {
            _state.update { it.copy(selected = address) }
        }
    }

    fun connect() {
        val address = _state.value.selected ?: return
        val device: BluetoothDevice = bluetoothAdapter()?.getRemoteDevice(address) ?: return
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            // Keeps reconnecting after a dropped link until Disconnect is tapped.
            // Waits 2, 4, 8, 16, then 30 s between attempts (spec section 4.4).
            var attempt = 0
            while (isActive) {
                reachedLive = false
                try {
                    run(ClassicTransport(device))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Stopped: ${e.message}")
                } finally {
                    transport?.close()
                    transport = null
                }
                attempt = if (reachedLive) 1 else attempt + 1
                val waitSeconds = minOf(30, 1 shl attempt)
                _state.update {
                    it.copy(phase = Phase.Failed, message = "Link lost, retrying in $waitSeconds s")
                }
                delay(waitSeconds * 1000L)
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        transport?.close()
        transport = null
        _state.update { it.copy(phase = Phase.Idle, message = "Disconnected") }
    }

    private suspend fun run(link: Transport) {
        _state.update {
            it.copy(
                phase = Phase.Connecting, message = "Opening Bluetooth link",
                version = "", protocol = "", voltage = "", supported = emptyList(), live = emptyMap(),
            )
        }
        log("Connecting to ${_state.value.selected}")
        link.connect()
        transport = link
        log("Bluetooth link open")

        val elm = ElmSession(link) { line -> log(line) }

        // Initialisation, spec section 4.2
        _state.update { it.copy(phase = Phase.Initialising, message = "Resetting adapter") }
        elm.send("ATZ", timeoutMs = 3000)
        delay(300)
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) elm.send(cmd)
        val version = elm.send("ATI").text
        _state.update { it.copy(version = version) }
        elm.send("ATSP0")

        _state.update { it.copy(message = "Waking the engine computer. This can take 10 s") }
        val first = elm.send("0100", timeoutMs = 15000)
        if (!first.ok) {
            throw IllegalStateException(
                (first.error ?: "No answer from the car") + ". Is the ignition on?"
            )
        }

        val protocolNumber = elm.send("ATDPN").text
        val protocolName = elm.send("ATDP").text
        val voltage = elm.send("ATRV").text
        _state.update { it.copy(protocol = "$protocolName ($protocolNumber)", voltage = voltage) }

        // Supported PIDs: each block says whether the next block exists (bit for PID base+0x20).
        val supported = mutableListOf<Int>()
        var block = Pids.supported(first, 0x00)
        supported += block
        var base = 0x20
        while (base <= 0xA0 && block.contains(base)) {
            block = Pids.supported(elm.send(Pids.command(base), timeoutMs = 3000), base)
            supported += block
            base += 0x20
        }
        // Drop the block markers (20, 40 ...) from the list shown on screen.
        val realPids = supported.filter { code -> code % 0x20 != 0 }
        _state.update { it.copy(supported = realPids) }
        log("Supported: " + supported.joinToString(" ") { "%02X".format(it) })

        // Speed-up. On ISO 9141-2 the adapter waits after each answer in case a second
        // computer replies. Adding "1" to a request tells it one answer is enough.
        // Build 1 measured about 280 ms per request without this.
        val trial = elm.send(Pids.command(0x0C) + "1", timeoutMs = 1500)
        val suffix = if (Pids.dataBytes(trial, 0x0C, 2) != null) "1" else ""
        log(if (suffix.isEmpty()) "Single-answer mode: not supported" else "Single-answer mode: on")
        elm.send("ATAT2") // more aggressive adaptive timing

        // Live loop
        val toPoll = livePids.filter { supported.isEmpty() || it in supported }
        reachedLive = true
        _state.update { it.copy(phase = Phase.Live, message = "Live") }
        while (true) {
            yield() // stops the loop cleanly when Disconnect is tapped
            val cycleStart = System.currentTimeMillis()
            for (code in toPoll) {
                val pid = Pids.BY_CODE[code] ?: continue
                val reply = elm.send(Pids.command(code) + suffix, timeoutMs = 1500)
                val value = Pids.decode(reply, pid) ?: continue
                _state.update { it.copy(live = it.live + (code to value)) }
            }
            val volts = elm.send("ATRV").text
            _state.update { it.copy(voltage = volts, cycleMs = System.currentTimeMillis() - cycleStart) }
        }
    }

    private fun log(line: String) {
        val stamped = clock.format(Date()) + "  " + line
        _state.update { it.copy(log = (it.log + stamped).takeLast(400)) }
    }

    /** Plain-text report for sharing. */
    fun report(): String {
        val s = _state.value
        val live = s.live.entries.sortedBy { it.key }.joinToString("\n") { (code, value) ->
            val pid = Pids.BY_CODE[code]
            "  %02X %s: %.1f %s".format(code, pid?.name ?: "", value, pid?.unit ?: "")
        }
        return buildString {
            appendLine("Civic OBD probe report")
            appendLine("Adapter: ${s.version}")
            appendLine("Protocol: ${s.protocol}")
            appendLine("Voltage: ${s.voltage}")
            appendLine("Supported PIDs: " + s.supported.joinToString(" ") { "%02X".format(it) })
            appendLine("Poll cycle: ${s.cycleMs} ms")
            appendLine("Last values:")
            appendLine(live)
            appendLine()
            appendLine("Log:")
            s.log.forEach { appendLine(it) }
        }
    }

    override fun onCleared() {
        job?.cancel()
        transport?.close()
    }
}
