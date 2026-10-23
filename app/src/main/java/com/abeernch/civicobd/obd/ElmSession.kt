package com.abeernch.civicobd.obd

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** One exchange with the adapter: what was sent and what came back. */
data class ElmReply(
    val command: String,
    val lines: List<String>,   // cleaned response lines, without echo or SEARCHING...
    val elapsedMs: Long,
    val error: String?,        // a known ELM error message, or null
    val timedOut: Boolean,
) {
    val ok: Boolean get() = error == null && !timedOut && lines.isNotEmpty()
    val text: String get() = lines.joinToString(" ")
}

/**
 * Sends one command at a time and waits for the '>' prompt before the next.
 * The mutex makes sure two callers never interleave commands.
 */
class ElmSession(
    private val transport: Transport,
    private val log: (String) -> Unit,
) {
    private val mutex = Mutex()

    suspend fun send(command: String, timeoutMs: Long = 2000): ElmReply = mutex.withLock {
        val start = System.currentTimeMillis()
        drainInput()
        transport.output.write((command + "\r").toByteArray(Charsets.US_ASCII))
        transport.output.flush()

        val buffer = StringBuilder()
        var gotPrompt = false
        while (System.currentTimeMillis() - start < timeoutMs) {
            val input = transport.input
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) throw IOException("Adapter closed the link")
                if (c.toChar() == '>') {
                    gotPrompt = true
                    break
                }
                buffer.append(c.toChar())
            } else {
                delay(5)
            }
        }

        val elapsed = System.currentTimeMillis() - start
        val lines = clean(buffer.toString(), command)
        val error = lines.firstOrNull { line -> ERRORS.any { line.uppercase().contains(it) } }
        val reply = ElmReply(command, lines.filter { it != error }, elapsed, error, !gotPrompt)

        val status = when {
            reply.timedOut -> "TIMEOUT"
            error != null -> error
            else -> reply.text
        }
        log("> $command   ${elapsed} ms   $status")
        reply
    }

    /** Throws away anything left over from a previous command. */
    private fun drainInput() {
        val input = transport.input
        while (input.available() > 0) input.read()
    }

    private fun clean(raw: String, command: String): List<String> =
        raw.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { !it.equals(command, ignoreCase = true) }   // echo, if still on
            .filter { !it.uppercase().startsWith("SEARCHING") }
            .map { it.removePrefix("BUS INIT: ...OK").removePrefix("BUS INIT: OK").trim() }
            .filter { it.isNotEmpty() }

    companion object {
        // Responses that mean "no usable data". Spec section 4.4.
        val ERRORS = listOf(
            "NO DATA", "UNABLE TO CONNECT", "BUS INIT", "BUS ERROR", "DATA ERROR",
            "RX ERROR", "STOPPED", "BUFFER FULL", "CAN ERROR", "LV RESET", "?",
        )
    }
}
