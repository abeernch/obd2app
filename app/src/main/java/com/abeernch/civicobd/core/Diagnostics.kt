package com.abeernch.civicobd.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A diagnostic trouble code, for example P0133. */
data class Dtc(val code: String) {
    val description: String get() = DtcText.describe(code)
}

data class DiagState(
    val stored: List<Dtc> = emptyList(),       // Mode 03: confirmed codes, these light the check-engine lamp
    val pending: List<Dtc> = emptyList(),      // Mode 07: seen once, not yet confirmed
    val freezeFrame: Map<Int, Double> = emptyMap(), // Mode 02: sensor values when the first code was stored
    val freezeCode: String? = null,
    val readAtMs: Long = 0,
    val busy: Boolean = false,
    val message: String? = null,
)

enum class DiagRequest { Read, Clear }

/**
 * Fault codes. The screens send requests here; the background service, which owns the adapter,
 * carries them out between polling cycles and publishes the result.
 */
object Diagnostics {
    private val _state = MutableStateFlow(DiagState())
    val state: StateFlow<DiagState> = _state
    val requests = Channel<DiagRequest>(Channel.CONFLATED)

    fun request(r: DiagRequest) {
        _state.update { it.copy(busy = true, message = null) }
        requests.trySend(r)
    }

    fun publish(change: (DiagState) -> DiagState) = _state.update(change)

    /**
     * Reads codes from Mode 03 or 07 reply lines, such as "430133000000".
     * Each code is two bytes; empty slots are 0000.
     */
    fun parseCodes(lines: List<String>, mode: Int): List<Dtc> {
        val prefix = "%02X".format(0x40 + mode)
        val out = mutableListOf<Dtc>()
        for (raw in lines) {
            val line = raw.replace(" ", "").uppercase()
            if (!line.startsWith(prefix)) continue
            val body = line.substring(2)
            var i = 0
            while (i + 4 <= body.length) {
                val chunk = body.substring(i, i + 4)
                if (chunk != "0000") decode(chunk)?.let { if (it !in out) out += it }
                i += 4
            }
        }
        return out
    }

    /** Two bytes to a code: the top two bits pick P, C, B or U, the rest are digits. */
    fun decode(hex4: String): Dtc? {
        val v = hex4.toIntOrNull(16) ?: return null
        val system = "PCBU"[(v shr 14) and 0x3]
        val first = (v shr 12) and 0x3
        val rest = "%03X".format(v and 0xFFF)
        return Dtc("$system$first$rest")
    }
}

/** Plain-language meaning of common generic codes, plus a few Honda ones seen on this engine family. */
object DtcText {
    private val known = mapOf(
        "P0100" to "Mass or volume air flow circuit",
        "P0101" to "Mass air flow sensor range or performance",
        "P0105" to "Manifold absolute pressure (MAP) sensor circuit",
        "P0106" to "MAP sensor range or performance",
        "P0107" to "MAP sensor circuit low",
        "P0108" to "MAP sensor circuit high",
        "P0110" to "Intake air temperature sensor circuit",
        "P0112" to "Intake air temperature sensor circuit low",
        "P0113" to "Intake air temperature sensor circuit high",
        "P0115" to "Engine coolant temperature sensor circuit",
        "P0116" to "Coolant temperature sensor range or performance",
        "P0117" to "Coolant temperature sensor circuit low",
        "P0118" to "Coolant temperature sensor circuit high",
        "P0120" to "Throttle position sensor circuit",
        "P0121" to "Throttle position sensor range or performance",
        "P0122" to "Throttle position sensor circuit low",
        "P0123" to "Throttle position sensor circuit high",
        "P0125" to "Coolant too cold for closed-loop fuel control",
        "P0128" to "Thermostat: coolant below regulating temperature",
        "P0130" to "Oxygen sensor circuit (bank 1, sensor 1)",
        "P0131" to "Oxygen sensor low voltage (bank 1, sensor 1)",
        "P0132" to "Oxygen sensor high voltage (bank 1, sensor 1)",
        "P0133" to "Oxygen sensor slow response (bank 1, sensor 1)",
        "P0134" to "Oxygen sensor no activity (bank 1, sensor 1)",
        "P0135" to "Oxygen sensor heater circuit (bank 1, sensor 1)",
        "P0136" to "Oxygen sensor circuit (bank 1, sensor 2)",
        "P0137" to "Oxygen sensor low voltage (bank 1, sensor 2)",
        "P0138" to "Oxygen sensor high voltage (bank 1, sensor 2)",
        "P0139" to "Oxygen sensor slow response (bank 1, sensor 2)",
        "P0141" to "Oxygen sensor heater circuit (bank 1, sensor 2)",
        "P0171" to "System too lean (bank 1)",
        "P0172" to "System too rich (bank 1)",
        "P0201" to "Injector circuit, cylinder 1",
        "P0202" to "Injector circuit, cylinder 2",
        "P0203" to "Injector circuit, cylinder 3",
        "P0204" to "Injector circuit, cylinder 4",
        "P0300" to "Random or multiple cylinder misfire",
        "P0301" to "Misfire, cylinder 1",
        "P0302" to "Misfire, cylinder 2",
        "P0303" to "Misfire, cylinder 3",
        "P0304" to "Misfire, cylinder 4",
        "P0325" to "Knock sensor circuit",
        "P0335" to "Crankshaft position sensor circuit",
        "P0340" to "Camshaft position sensor circuit",
        "P0401" to "EGR flow insufficient",
        "P0420" to "Catalyst efficiency below threshold (bank 1)",
        "P0440" to "Evaporative emission system",
        "P0441" to "Evaporative emission system incorrect purge flow",
        "P0442" to "Evaporative emission system small leak",
        "P0443" to "Evaporative emission purge valve circuit",
        "P0446" to "Evaporative emission vent control circuit",
        "P0451" to "Fuel tank pressure sensor range or performance",
        "P0455" to "Evaporative emission system large leak",
        "P0456" to "Evaporative emission system very small leak",
        "P0500" to "Vehicle speed sensor",
        "P0505" to "Idle air control system",
        "P0506" to "Idle speed lower than expected",
        "P0507" to "Idle speed higher than expected",
        "P0560" to "System voltage",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0600" to "Engine computer communication link",
        "P0601" to "Engine computer memory checksum",
        "P0603" to "Engine computer keep-alive memory",
        "P0700" to "Transmission control system",
        "P1259" to "VTEC system (Honda)",
        "P1456" to "Evaporative emission leak, fuel tank side (Honda)",
        "P1457" to "Evaporative emission leak, canister side (Honda)",
    )

    fun describe(code: String): String = known[code] ?: when (code.firstOrNull()) {
        'P' -> if (code.getOrNull(1) == '1') "Manufacturer-specific powertrain code" else "Generic powertrain code"
        'C' -> "Chassis code"
        'B' -> "Body code"
        'U' -> "Network code"
        else -> "Unknown code"
    }
}
