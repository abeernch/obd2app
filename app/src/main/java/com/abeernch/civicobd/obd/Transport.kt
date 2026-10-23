package com.abeernch.civicobd.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** A byte pipe to the ELM327. Bluetooth Classic now, BLE and Wi-Fi later. */
interface Transport {
    val input: InputStream
    val output: OutputStream
    fun connect()
    fun close()
}

/** Bluetooth Classic serial link (SPP). Used by most ELM327 clones. */
@SuppressLint("MissingPermission") // The activity asks for Bluetooth permission before this runs.
class ClassicTransport(private val device: BluetoothDevice) : Transport {

    private var socket: BluetoothSocket? = null

    override val input: InputStream
        get() = socket?.inputStream ?: throw IOException("Not connected")

    override val output: OutputStream
        get() = socket?.outputStream ?: throw IOException("Not connected")

    /** Tries the standard socket first, then two fallbacks that some clones need. */
    override fun connect() {
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                // Direct channel 1, for adapters with a broken service record.
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.invoke(device, 1) as BluetoothSocket
            },
        )
        var lastError: Exception? = null
        for (makeSocket in attempts) {
            try {
                val s = makeSocket()
                s.connect()
                socket = s
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("Could not open a Bluetooth link: ${lastError?.message}")
    }

    override fun close() {
        try {
            socket?.close()
        } catch (ignored: IOException) {
        }
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
