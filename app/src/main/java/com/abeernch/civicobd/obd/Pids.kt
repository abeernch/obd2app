package com.abeernch.civicobd.obd

/** One Mode 01 parameter: how many data bytes it returns and how to turn them into a number. */
data class Pid(
    val code: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val decode: (IntArray) -> Double,
)

object Pids {

    // SAE J1979 formulas. A = first data byte, B = second.
    val ALL: List<Pid> = listOf(
        Pid(0x04, "Engine load", "%", 1) { it[0] * 100.0 / 255 },
        Pid(0x05, "Coolant", "°C", 1) { it[0] - 40.0 },
        Pid(0x06, "Short fuel trim", "%", 1) { (it[0] - 128) * 100.0 / 128 },
        Pid(0x07, "Long fuel trim", "%", 1) { (it[0] - 128) * 100.0 / 128 },
        Pid(0x0B, "Intake pressure", "kPa", 1) { it[0].toDouble() },
        Pid(0x0C, "RPM", "rpm", 2) { (it[0] * 256 + it[1]) / 4.0 },
        Pid(0x0D, "Speed", "km/h", 1) { it[0].toDouble() },
        Pid(0x0E, "Timing advance", "°", 1) { it[0] / 2.0 - 64 },
        Pid(0x0F, "Intake air", "°C", 1) { it[0] - 40.0 },
        Pid(0x10, "Air flow", "g/s", 2) { (it[0] * 256 + it[1]) / 100.0 },
        Pid(0x11, "Throttle", "%", 1) { it[0] * 100.0 / 255 },
        Pid(0x2F, "Fuel level", "%", 1) { it[0] * 100.0 / 255 },
        Pid(0x31, "Distance since codes cleared", "km", 2) { (it[0] * 256 + it[1]).toDouble() },
        Pid(0x42, "Module voltage", "V", 2) { (it[0] * 256 + it[1]) / 1000.0 },
    )

    val BY_CODE: Map<Int, Pid> = ALL.associateBy { it.code }

    /** Command text for a Mode 01 request, for example 0x0C gives "010C". */
    fun command(code: Int): String = "01%02X".format(code)

    /**
     * Finds the data bytes for [code] in a reply such as "410C1AF8".
     * Returns null if the reply does not contain a valid answer.
     */
    fun dataBytes(reply: ElmReply, code: Int, count: Int): IntArray? {
        if (!reply.ok) return null
        val hex = reply.lines.joinToString("").replace(" ", "").uppercase()
        val marker = "41%02X".format(code)
        val at = hex.indexOf(marker)
        if (at < 0) return null
        val start = at + marker.length
        if (hex.length < start + count * 2) return null
        return try {
            IntArray(count) { i -> hex.substring(start + i * 2, start + i * 2 + 2).toInt(16) }
        } catch (ignored: NumberFormatException) {
            null
        }
    }

    fun decode(reply: ElmReply, pid: Pid): Double? =
        dataBytes(reply, pid.code, pid.bytes)?.let { pid.decode(it) }

    /**
     * Reads a "which PIDs are supported" reply (0100, 0120, 0140 ...).
     * Four bytes, 32 bits, most significant bit first. Bit n set means PID base + n + 1 exists.
     */
    fun supported(reply: ElmReply, base: Int): List<Int> {
        val bytes = dataBytes(reply, base, 4) ?: return emptyList()
        val list = mutableListOf<Int>()
        for (n in 0 until 32) {
            val byte = bytes[n / 8]
            val bit = 7 - (n % 8)
            if ((byte shr bit) and 1 == 1) list.add(base + n + 1)
        }
        return list
    }
}
