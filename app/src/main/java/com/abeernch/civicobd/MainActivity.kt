package com.abeernch.civicobd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import com.abeernch.civicobd.core.Car
import com.abeernch.civicobd.core.CarStatus
import com.abeernch.civicobd.core.FuelLog
import com.abeernch.civicobd.core.Tank
import com.abeernch.civicobd.ui.CarCard
import com.abeernch.civicobd.ui.FuelScreen
import com.abeernch.civicobd.ui.HudScreen
import com.abeernch.civicobd.ui.TankCard
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.abeernch.civicobd.core.Backup
import com.abeernch.civicobd.ui.Appearance
import com.abeernch.civicobd.ui.Palette
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
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.abeernch.civicobd.core.CsvExport
import com.abeernch.civicobd.core.CsvImport
import com.abeernch.civicobd.core.ImportSummary
import com.abeernch.civicobd.core.RecordingRow
import com.abeernch.civicobd.core.Recordings
import com.abeernch.civicobd.core.TripRow
import com.abeernch.civicobd.ui.ExportKind
import com.abeernch.civicobd.ui.LogsScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var settings: AppSettings
    private lateinit var store: TripStore
    private lateinit var recordings: Recordings
    private lateinit var fuel: FuelLog

    // Bumped when fill-ups or car settings change, so tank and service figures refresh.
    private var fuelTick by mutableStateOf(0)

    private val pickBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) restoreBackup(uri) }

    // True while the app is shrunk to the floating window over another app.
    private var inPip by mutableStateOf(false)

    // Bumped whenever an appearance setting changes, so the theme is re-applied.
    private var appearanceTick by mutableStateOf(0)

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> importFiles(uris) }

    // Asks once for Bluetooth (Android 12+) and notifications (Android 13+), then starts the service.
    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startServiceIfAllowed()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        // Map tiles are cached inside the app's own folders; the user agent identifies the app to the tile server.
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
        store = TripStore.get(this)
        recordings = Recordings(store)
        fuel = FuelLog(store)
        LiveBus.update { it.copy(fuelPrice = settings.fuelPrice) }

        // Automated test hooks: "demo" starts the simulated car, "import_path" imports a CSV from a file path.
        // They exist only in the emulator test build, so no other app can trigger them on the phone.
        if (BuildConfig.TEST_HOOKS && intent.hasExtra("demo")) {
            settings.demo = intent.getBooleanExtra("demo", false)
            settings.askedBattery = true
        }
        // Test build only: a full tank two days ago and one 30 L drive since, so the fuel gauge has a level to show.
        if (BuildConfig.TEST_HOOKS && intent.getBooleanExtra("seed_fuel", false) && fuel.all().isEmpty()) {
            val day = 86_400_000L
            val now = System.currentTimeMillis()
            fuel.add(com.abeernch.civicobd.core.Fillup(timeMs = now - 2 * day, litres = 45.0, price = 378.0, full = true, odometer = null), settings)
            store.insert(TripRow(startMs = now - day, endMs = now - day + 3_600_000, distanceKm = 420.0, fuelL = 30.0, driveS = 3_600.0))
        }
        intent.getStringExtra("import_path")?.takeIf { BuildConfig.TEST_HOOKS }?.let { path ->
            lifecycleScope.launch {
                val message = withContext(Dispatchers.IO) {
                    val file = File(path)
                    importFrom(file.name) { file.bufferedReader(Charsets.UTF_8) }
                }
                LiveBus.log("Import: $message")
                LiveBus.tripsChanged()
            }
        }

        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) needed += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) needed += "android.permission.POST_NOTIFICATIONS"
        needed += Manifest.permission.ACCESS_FINE_LOCATION
        needed += Manifest.permission.ACCESS_COARSE_LOCATION
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startServiceIfAllowed() else askPermissions.launch(missing.toTypedArray())

        // First run only: ask to be exempt from battery optimisation so auto-connect keeps working.
        if (!settings.askedBattery) {
            settings.askedBattery = true
            requestBatteryExemption()
        }

        // Apply the saved look before the first frame, so there is no flash of the wrong theme.
        val systemDarkNow = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        Appearance.apply(settings, systemDarkNow)

        setContent {
            val systemDark = isSystemInDarkTheme()
            val tick = appearanceTick
            SideEffect {
                Appearance.apply(settings, systemDark)
                // Status and navigation bars follow the theme, with dark icons on the light theme.
                window.statusBarColor = Palette.Background.toArgb()
                window.navigationBarColor = Palette.Surface.toArgb()
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !Palette.isDark
                    isAppearanceLightNavigationBars = !Palette.isDark
                }
                if (tick < 0) Unit // keeps the tick read, so a change re-runs this effect
            }
            val live by LiveBus.state.collectAsState()
            var todayRows by remember { mutableStateOf(emptyList<TripRow>()) }
            var weekRows by remember { mutableStateOf(emptyList<TripRow>()) }
            var allRows by remember { mutableStateOf(emptyList<TripRow>()) }

            // Saved trips refresh whenever the service writes one (about every 10 s while driving).
            LaunchedEffect(live.tripsVersion) {
                withContext(Dispatchers.IO) {
                    todayRows = Period.Today.range().let { (a, b) -> store.between(a, b) }
                    weekRows = Period.Week.range().let { (a, b) -> store.between(a, b) }
                    allRows = Period.All.range().let { (a, b) -> store.between(a, b) }
                }
            }
            // The trip in progress is counted from live totals, not its last save, so "today" never lags the trip.
            fun withLive(rows: List<TripRow>): Summary {
                val t = live.trip
                val saved = rows.filter { it.id != t.id }
                if (!t.active) return Summary.of(saved)
                return Summary.of(
                    saved + TripRow(
                        id = t.id, startMs = t.startMs, endMs = System.currentTimeMillis(),
                        distanceKm = t.distanceKm, fuelL = t.fuelL, cost = t.fuelL * live.fuelPrice,
                        driveS = t.driveS, idleS = t.idleS, maxSpeed = t.maxSpeed,
                    )
                )
            }
            val today = withLive(todayRows)
            val week = withLive(weekRows)
            val total = withLive(allRows)

            // Tank, range, odometer and oil reminder, refreshed as trips are saved.
            var tank by remember { mutableStateOf<Tank?>(null) }
            var car by remember { mutableStateOf(CarStatus(null, null)) }
            LaunchedEffect(live.tripsVersion, fuelTick) {
                withContext(Dispatchers.IO) {
                    val t = live.trip
                    tank = fuel.tank(settings.tankL, if (t.active) t.fuelL else 0.0, if (t.active) t.distanceKm else 0.0)
                    car = Car.status(settings, store, if (live.trip.active) live.trip.distanceKm else 0.0)
                }
            }
            var fuelOpen by remember { mutableStateOf(false) }
            var starts by remember { mutableStateOf(emptyList<com.abeernch.civicobd.core.CarEvent>()) }
            LaunchedEffect(live.tripsVersion) {
                starts = withContext(Dispatchers.IO) { com.abeernch.civicobd.core.Events(store).recent("start") }
            }

            // Keep the screen awake while a trip runs, if chosen.
            val view = LocalView.current
            val awake = live.trip.active && settings.keepScreenOn
            DisposableEffect(awake) {
                view.keepScreenOn = awake
                onDispose { view.keepScreenOn = false }
            }
            // Jump to the Drive screen when a trip starts, if chosen.
            var driveJump by remember { mutableStateOf(0) }
            LaunchedEffect(live.trip.id) {
                if (live.trip.active && settings.showDriveOnStart) driveJump++
            }

            // Head-up display: full screen, full brightness, screen kept on.
            var hud by remember { mutableStateOf(false) }
            DisposableEffect(hud) {
                val attrs = window.attributes
                attrs.screenBrightness = if (hud) 1f else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
                if (hud) view.keepScreenOn = true
                onDispose { if (hud) view.keepScreenOn = awake }
            }
            // The floating window follows the trip: it may open only while one is running.
            LaunchedEffect(live.trip.active) { updatePip() }
            if (inPip) {
                com.abeernch.civicobd.ui.PipView(live)
                return@setContent
            }
            if (hud) {
                HudScreen(live) { hud = false }
                return@setContent
            }

            AppShell(live, driveJump) { tab ->
                when (tab) {
                    Tab.Drive -> DriveScreen(
                        live, today, tank, settings = settings,
                        locationOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED,
                        onHud = { hud = true },
                    )
                    Tab.Sensors -> SensorsScreen(live, today, week, total, onShareLog = ::shareLog, starts = starts)
                    Tab.Stats -> if (fuelOpen) {
                        FuelScreen(
                            fuel = fuel, settings = settings, tank = tank, version = fuelTick,
                            onBack = { fuelOpen = false },
                            onChanged = {
                                fuelTick++
                                LiveBus.update { it.copy(fuelPrice = settings.fuelPrice) }
                            },
                        )
                    } else {
                        StatsScreen(store, live.tripsVersion, onExportTrips = ::exportTrips) {
                            TankCard(tank) { fuelOpen = true }
                            CarCard(car)
                        }
                    }
                    Tab.Logs -> LogsScreen(
                        recordings = recordings,
                        version = live.tripsVersion,
                        onImport = { pickFiles.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
                        onExport = { rec, _, kind -> exportRecording(rec, kind) },
                        settings = settings,
                        onExportRoute = { rec, pts, gpx, scale, range -> exportRoute(rec, pts, gpx, scale, range) },
                        onDelete = { rec ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                recordings.delete(rec.id, withTrip = rec.source != "Live")
                                LiveBus.tripsChanged()
                            }
                        },
                    )
                    Tab.Settings -> SettingsScreen(
                        settings = settings,
                        paired = pairedDevices(),
                        onChanged = {
                            fuelTick++
                            LiveBus.update { it.copy(fuelPrice = settings.fuelPrice) }
                        },
                        onBattery = ::requestBatteryExemption,
                        onLocationAlways = ::requestBackgroundLocation,
                        locationStatus = locationStatus(),
                        onAppearance = { appearanceTick++ },
                        onBackup = ::backupAll,
                        onFloatingChanged = ::updatePip,
                        onRestore = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    )
                }
            }
        }
    }

    // ---- Floating window (picture in picture) ----

    private fun pipSupported() =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Whether leaving the app now should shrink it to the floating window. */
    private fun wantsPip() = settings.floatingWindow && LiveBus.state.value.trip.active && pipSupported()

    private fun pipParams(): android.app.PictureInPictureParams {
        val b = android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9))
        // Android 12 and newer shrink the app smoothly on the home gesture when this is set.
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(wantsPip()).setSeamlessResizeEnabled(false)
        return b.build()
    }

    private fun updatePip() {
        if (!pipSupported()) return
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) { }
    }

    // Android 8 to 11: shrink when the user presses home or switches apps.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && wantsPip()) {
            try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) { }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    // ---- Backup ----

    private fun backupAll() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { Backup.create(this@MainActivity, exportDir()) }
            shareFile(file, "application/zip")
        }
    }

    /** Replaces all data with the backup, then restarts the app so every part reopens the new database. */
    private fun restoreBackup(uri: Uri) {
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    stopService(Intent(this@MainActivity, ObdService::class.java))
                    contentResolver.openInputStream(uri)!!.use { Backup.restore(this@MainActivity, it) }
                    null
                } catch (e: Exception) {
                    e.message ?: "Restore failed"
                }
            }
            if (error != null) {
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                ObdService.start(this@MainActivity)
                return@launch
            }
            Toast.makeText(this@MainActivity, "Backup restored. Restarting.", Toast.LENGTH_SHORT).show()
            val again = Intent(this@MainActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(again)
            Runtime.getRuntime().exit(0)
        }
    }

    // ---- Import ----

    private fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val messages = withContext(Dispatchers.IO) { uris.map { importOne(it) } }
            LiveBus.tripsChanged()
            Toast.makeText(this@MainActivity, messages.joinToString("\n"), Toast.LENGTH_LONG).show()
        }
    }

    private fun importOne(uri: Uri): String {
        val name = displayName(uri)
        if (name.lowercase().endsWith(".brc")) {
            return "$name: BRC is Car Scanner's closed format. Export as CSV #1 instead."
        }
        return importFrom(name) { contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8) }
    }

    private fun importFrom(name: String, open: () -> java.io.BufferedReader): String {
        return try {
            val log = open().use { CsvImport.parse(it, name) }
            if (recordings.exists(log.startMs, "Car Scanner")) return "$name: already imported"
            val trip = ImportSummary.trip(log, settings.fuelPrice)
            val tripId = store.insert(trip)
            val recId = recordings.create(log.startMs, "Car Scanner", name, tripId)
            recordings.add(recId, log.samples)
            recordings.setEnd(recId, log.endMs)
            val channels = log.samples.map { it.name }.distinct().size
            "$name: %.1f km, %d parameters imported".format(trip.distanceKm, channels)
        } catch (e: Exception) {
            "$name: could not import (${e.message})"
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "file"
        }
        return uri.lastPathSegment ?: "file"
    }

    // ---- Export ----

    private fun exportRecording(rec: RecordingRow, kind: ExportKind) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val all = recordings.channels(rec.id).map { recordings.series(rec.id, it) }
                when (kind) {
                    ExportKind.Vertical -> CsvExport.vertical(exportDir(), rec, all)
                    ExportKind.Table -> CsvExport.horizontal(exportDir(), rec, all)
                }
            }
            shareFile(file)
        }
    }

    /** GPX or KML of a route, with the first and last stretch removed for privacy if chosen. */
    private fun exportRoute(
        rec: RecordingRow,
        points: List<com.abeernch.civicobd.core.RoutePoint>,
        gpx: Boolean,
        scale: com.abeernch.civicobd.core.ColorScale,
        range: Pair<Double, Double>,
    ) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                val trimmed = com.abeernch.civicobd.core.Route.trimForPrivacy(points, settings.privacyTrimM)
                val name = "civic-route_" + java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date(rec.startMs))
                if (gpx) com.abeernch.civicobd.core.RouteExport.gpx(exportDir(), name, trimmed)
                else com.abeernch.civicobd.core.RouteExport.kml(exportDir(), name, trimmed, scale, range)
            }
            shareFile(file, if (gpx) "application/gpx+xml" else "application/vnd.google-earth.kml+xml")
        }
    }

    private fun exportTrips(trips: List<TripRow>) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { CsvExport.trips(exportDir(), trips) }
            shareFile(file)
        }
    }

    private fun exportDir(): File = File(cacheDir, "exports").apply { mkdirs() }

    private fun shareFile(file: File, mime: String = "text/csv") {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Export ${file.name}"))
    }

    private val askBackgroundLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { appearanceTick++ }

    /** Routes are recorded by the background service, so location must be allowed "all the time". */
    private fun requestBackgroundLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine) {
            askPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            askBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun locationStatus(): String {
        fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        return when {
            !has(Manifest.permission.ACCESS_FINE_LOCATION) -> "Off. Routes are not recorded."
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
                "Only while the app is open. Routes may be missed when it starts on its own."
            else -> "Allowed all the time."
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
