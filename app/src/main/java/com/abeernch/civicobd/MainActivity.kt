package com.abeernch.civicobd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.abeernch.civicobd.core.AppSettings
import com.abeernch.civicobd.core.LiveBus
import com.abeernch.civicobd.core.Period
import com.abeernch.civicobd.core.Summary
import com.abeernch.civicobd.core.TripStore
import com.abeernch.civicobd.service.ObdService
import com.abeernch.civicobd.ui.AppShell
import com.abeernch.civicobd.ui.DriveScreen
import com.abeernch.civicobd.ui.PairedDevice
import com.abeernch.civicobd.ui.SensorsScreen
import com.abeernch.civicobd.ui.SettingsScreen
import com.abeernch.civicobd.ui.StatsScreen
import com.abeernch.civicobd.ui.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var settings: AppSettings
    private lateinit var store: TripStore

    // Asks once for Bluetooth (Android 12+) and notifications (Android 13+), then starts the service.
    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startServiceIfAllowed()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        store = TripStore.get(this)
        LiveBus.update { it.copy(fuelPrice = settings.fuelPrice) }

        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) needed += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) needed += "android.permission.POST_NOTIFICATIONS"
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startServiceIfAllowed() else askPermissions.launch(missing.toTypedArray())

        // First run only: ask to be exempt from battery optimisation so auto-connect keeps working.
        if (!settings.askedBattery) {
            settings.askedBattery = true
            requestBatteryExemption()
        }

        setContent {
            val live by LiveBus.state.collectAsState()
            var today by remember { mutableStateOf(Summary()) }
            var week by remember { mutableStateOf(Summary()) }
            var total by remember { mutableStateOf(Summary()) }

            // Totals refresh whenever the service writes a trip (about every 10 s while driving).
            LaunchedEffect(live.tripsVersion) {
                withContext(Dispatchers.IO) {
                    today = store.summary(Period.Today)
                    week = store.summary(Period.Week)
                    total = store.summary(Period.All)
                }
            }

            AppShell(live) { tab ->
                when (tab) {
                    Tab.Drive -> DriveScreen(live, today)
                    Tab.Sensors -> SensorsScreen(live, today, week, total, onShareLog = ::shareLog)
                    Tab.Stats -> StatsScreen(store, live.tripsVersion)
                    Tab.Settings -> SettingsScreen(
                        settings = settings,
                        paired = pairedDevices(),
                        onChanged = { LiveBus.update { it.copy(fuelPrice = settings.fuelPrice) } },
                        onBattery = ::requestBatteryExemption,
                    )
                }
            }
        }
    }

    private fun startServiceIfAllowed() {
        val ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) {
            ObdService.start(this)
        } else {
            LiveBus.update { it.copy(status = "Allow Bluetooth to connect") }
        }
    }

    @SuppressLint("BatteryLife") // Personal app, never published, so the direct request is fine.
    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power?.isIgnoringBatteryOptimizations(packageName) == true) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    @SuppressLint("MissingPermission")
    private fun pairedDevices(): List<PairedDevice> = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
            ?.map { PairedDevice(it.name ?: "Unnamed device", it.address) }
            ?.sortedBy { it.name }
            ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun shareLog() {
        val s = LiveBus.state.value
        val text = buildString {
            appendLine("Civic OBD connection log")
            appendLine("Status: ${s.status}")
            appendLine("Adapter: ${s.adapter}")
            appendLine("Protocol: ${s.protocol}")
            appendLine("Supported PIDs: " + s.supported.joinToString(" ") { "%02X".format(it) })
            appendLine("Refresh cycle: ${s.cycleMs} ms")
            appendLine()
            s.log.forEach { appendLine(it) }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share log"))
    }
}
