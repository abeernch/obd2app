package com.abeernch.civicobd.obd

import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A pretend ELM327 plugged into a pretend 2005 Civic, for demos and automated tests.
 * Speaks the same text protocol as the real adapter, reports ISO 9141-2 and the same
 * supported PIDs as the real car, and drives a repeating 160 s loop:
 * idle, accelerate through the gears, cruise, coast in gear (fuel cut), stop, engine off.
 */
class SimTransport : Transport {

    private val startMs = System.currentTimeMillis()
    private val pending = ArrayDeque<Int>()
    private var readyAt = 0L
    private val lock = Object()
    private var open = false
    private var codeStored = true

    override val input: InputStream = object : InputStream() {
        override fun available(): Int = synchronized(lock) {
            if (System.currentTimeMillis() < readyAt) 0 else pending.size
        }

        override fun read(): Int = synchronized(lock) {
            if (pending.isEmpty()) -1 else pending.removeFirst()
        }
    }

    override val output: OutputStream = object : OutputStream() {
        private val command = StringBuilder()
        override fun write(b: Int) {
            val c = b.toChar()
            if (c == '\r') {
                answer(command.toString().trim().uppercase())
                command.clear()
            } else {
                command.append(c)
            }
        }
    }

    override fun connect() {
        Thread.sleep(400) // a real Bluetooth connect takes a moment
        open = true
    }

    override fun close() {
        open = false
    }

    private fun answer(cmd: String) {
        val reply = respond(cmd)
        synchronized(lock) {
            "$reply\r\r>".forEach { pending.addLast(it.code) }
            // Real K-line answers take about 60 to 120 ms with the single-answer speed-up.
            readyAt = System.currentTimeMillis() + if (cmd.startsWith("AT")) 15 else 70
        }
    }

    private fun respond(cmd: String): String {
        if (cmd.startsWith("AT")) {
            return when {
                cmd == "ATZ" -> "ELM327 v2.1"
                cmd == "ATI" -> "ELM327 v2.1 (simulated)"
                cmd == "ATDPN" -> "A3"
                cmd == "ATDP" -> "AUTO, ISO 9141-2"
                cmd == "ATRV" -> if (state().rpm > 0) "14.1V" else "12.5V"
                else -> "OK"
            }
        }
        // Fault codes: one harmless stored code (P0456, small evaporative leak) until it is cleared.
        when (cmd) {
            "03" -> return if (codeStored) "430456000000" else "430000000000"
            "07" -> return "470000000000"
            "04" -> { codeStored = false; return "44" }
        }
        // Mode 02 freeze frame: answer like Mode 01 with the frame number, from the current state.
        if (cmd.length == 6 && cmd.startsWith("02") && cmd.endsWith("00")) {
            if (!codeStored) return "NO DATA"
            val pid = cmd.substring(2, 4)
            if (pid == "02") return "4202000456"
            val asMode1 = respond("01$pid")
            return if (asMode1.startsWith("41")) "42" + asMode1.substring(2, 4) + "00" + asMode1.substring(4) else asMode1
        }
        // Mode 01 request, with or without the trailing "1"
        val body = if (cmd.length == 5 && cmd.endsWith("1")) cmd.dropLast(1) else cmd
        if (body.length != 4 || !body.startsWith("01")) return "?"
        val pid = body.substring(2).toIntOrNull(16) ?: return "?"
        val s = state()
        val data: List<Int> = when (pid) {
            0x00 -> supportedMask()
            0x01 -> listOf(if (codeStored) 0x01 else 0x00, 0x07, 0xE5, 0x00)
            0x04 -> listOf((s.load * 255 / 100).roundToInt())
            0x05 -> listOf((s.coolant + 40).roundToInt())
            0x0B -> listOf(s.map.roundToInt())
            0x0C -> (s.rpm * 4).roundToInt().let { listOf(it shr 8, it and 0xFF) }
            0x0D -> listOf(s.speed.roundToInt())
            0x0E -> listOf(((s.timing + 64) * 2).roundToInt())
            0x0F -> listOf((s.iat + 40).roundToInt())
            0x11 -> listOf((s.throttle * 255 / 100).roundToInt())
            0x13 -> listOf(0x03)
            else -> return "NO DATA"
        }
        return "41%02X".format(pid) + data.joinToString("") { "%02X".format(it.coerceIn(0, 255)) }
    }

    /** PIDs 01 04 05 0B 0C 0D 0E 0F 11 13, the same set the real car reported on 23 Oct. */
    private fun supportedMask(): List<Int> {
        var bits = 0L
        for (p in listOf(0x01, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x13)) {
            bits = bits or (1L shl (32 - p))
        }
        return listOf(((bits shr 24) and 0xFF).toInt(), ((bits shr 16) and 0xFF).toInt(), ((bits shr 8) and 0xFF).toInt(), (bits and 0xFF).toInt())
    }

    private data class CarState(
        val speed: Double, val rpm: Double, val throttle: Double, val map: Double,
        val load: Double, val coolant: Double, val iat: Double, val timing: Double,
    )

    // km/h per 1000 rpm, gears 1 to 5. Same table the gear estimator uses.
    private val ratios = listOf(8.1, 13.6, 20.5, 26.7, 34.8)

    private fun gearFor(speed: Double) = when {
        speed < 15 -> 0
        speed < 30 -> 1
        speed < 45 -> 2
        speed < 60 -> 3
        else -> 4
    }

    private fun state(): CarState {
        val total = (System.currentTimeMillis() - startMs) / 1000.0
        val t = total % 160.0
        val wobble = sin(total * 1.7) * 0.6
        val coolant = min(88.0, 62.0 + total / 8.0)

        fun moving(speed: Double, throttle: Double, map: Double, inGear: Boolean): CarState {
            val rpm = if (inGear && speed > 3) max(900.0, speed / ratios[gearFor(speed)] * 1000) else 790.0 + wobble * 10
            val load = (map / 100.0 * 90).coerceIn(15.0, 95.0)
            return CarState(speed, rpm, throttle, map + wobble, load, coolant, 32.0, if (throttle > 25) 18.0 else 32.0)
        }

        return when {
            t < 15 -> moving(0.0, 9.8, 30.0, false)
            t < 45 -> moving((t - 15) / 30.0 * 72.0, 32.0 + wobble, 72.0, true)
            t < 95 -> moving(72.0 + wobble, 16.0, 42.0, true)
            t < 115 -> moving(72.0 - (t - 95) / 20.0 * 50.0, 9.8, 22.0, true)       // coasting in gear: fuel cut
            t < 130 -> moving(max(0.0, 22.0 - (t - 115) / 15.0 * 22.0), 9.8, 30.0, false)
            t < 145 -> moving(0.0, 9.8, 30.0, false)
            else -> CarState(0.0, 0.0, 9.8, 96.0, 0.0, coolant, 32.0, 0.0)          // engine off
        }
    }
}
