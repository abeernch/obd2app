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
import androidx.compose.foundation.layout.fillMaxSize
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

    // ---- The drive welcome: played when the app is opened for a drive ----

    // True when the welcome should play: a fresh start, or back after five minutes or more away.
    private var introArmed by mutableStateOf(false)
    // The app under the welcome may rise into view (from 6.2 s into it).
    private var introRevealed by mutableStateOf(false)
    // When the Drive tab's cluster sweeps awake, set by the welcome.
    private var wakeAtMs by mutableStateOf(0L)
    // The splash has gone (or never showed), so the welcome may start.
    private var splashOver by mutableStateOf(false)
    // When the app last went out of sight; kept across rotation.
    private var stoppedAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        stoppedAtMs = savedInstanceState?.getLong("stoppedAtMs") ?: 0L
        introArmed = savedInstanceState == null
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
            store.insert(TripRow(startMs = now - day, endMs = now - day + 7 * 3_600_000, distanceKm = 420.0, fuelL = 30.0, driveS = 7 * 3_600.0))
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
        Appearance.upgradeOnce(settings)
        if (BuildConfig.TEST_HOOKS) {
            com.abeernch.civicobd.ui.TestFlags.apply {
                noIntro = intent.getBooleanExtra("no_intro", false)
                rectClip = intent.getBooleanExtra("rect_clip", false)
                noPage = intent.getBooleanExtra("no_page", false)
                classicFont = intent.getBooleanExtra("classic_font", false)
                noCluster = intent.getBooleanExtra("no_cluster", false)
            }
            if (com.abeernch.civicobd.ui.TestFlags.noIntro) introArmed = false
            if (com.abeernch.civicobd.ui.TestFlags.classicFont) settings.face = com.abeernch.civicobd.ui.Face.Barlow.name
        }
        Appearance.apply(settings, systemDarkNow)

        handleOpen(intent)
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

            // The drive that has just ended, for the goodbye on Drive. Cleared when the next one starts.
            var completed by remember { mutableStateOf<com.abeernch.civicobd.ui.CompletedDrive?>(null) }
            // Between the drive ending and its figures loading, Drive stays blank rather than flashing the cluster.
            var goodbyePending by remember { mutableStateOf(false) }
            val previousTrip = remember { longArrayOf(live.trip.id) }
            LaunchedEffect(live.trip.id) {
                val id = live.trip.id
                val ended = previousTrip[0]
                previousTrip[0] = id
                if (id >= 0) {
                    completed = null
                    goodbyePending = false
                } else if (ended >= 0) {
                    goodbyePending = true
                    // A drive under 100 m is not kept, so there is nothing to say goodbye to.
                    val row = withContext(Dispatchers.IO) { store.byId(ended) }
                    completed = row?.let {
                        com.abeernch.civicobd.ui.CompletedDrive(withContext(Dispatchers.IO) { com.abeernch.civicobd.core.TripSummary.of(it, store) }, null)
                    }
                    goodbyePending = false
                    // How it compares on its route reads every drive's end points, so it follows a moment later.
                    if (row != null) {
                        val route = withContext(Dispatchers.IO) {
                            runCatching {
                                com.abeernch.civicobd.core.RouteContexts.of(row, store, recordings, com.abeernch.civicobd.ui.routeNames(settings))
                            }.getOrNull()
                        }
                        if (route != null && completed?.summary?.trip?.id == row.id) completed = completed?.copy(route = route)
                    }
                }
            }

            // The welcome: not while moving (the gauges matter more), and never over the HUD or floating window.
            LaunchedEffect(introArmed) {
                if (!introArmed) return@LaunchedEffect
                val now = LiveBus.state.value
                val moving = now.link == com.abeernch.civicobd.core.Link.Live && (now.values[0x0D] ?: 0.0) > 5
                if (moving) {
                    introArmed = false
                } else {
                    completed = null
                    introRevealed = false
                    driveJump++
                }
            }
            val introOn = introArmed && !hud && !inPip

            // The app stays composed underneath the head-up display and the floating window, so
            // leaving either returns to the same tab, without the splash playing again.
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            AppShell(live, driveJump, jump, hold = introOn && !introRevealed, onSplashFinished = { splashOver = true }) { tab ->
                when (tab) {
                    Tab.Drive -> DriveScreen(
                        live, today, tank, settings = settings,
                        locationOk = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) ==
                            PackageManager.PERMISSION_GRANTED,
                        onHud = { hud = true },
                        completed = completed,
                        goodbyePending = goodbyePending,
                        onGoodbyePlayed = { completed = completed?.copy(played = true) },
                        onViewDrive = { id ->
                            openTripId = id
                            jump = Tab.Trips to ++jumpCount
                        },
                        wakeAtMs = wakeAtMs,
                        onLocation = ::openLocation,
                    )
                    Tab.Trips -> com.abeernch.civicobd.ui.TripsScreen(
                        store = store,
                        recordings = recordings,
                        version = live.tripsVersion,
                        settings = settings,
                        openTripId = openTripId,
                        onOpened = { openTripId = null },
                        onExport = { rec, _, kind -> exportRecording(rec, kind) },
                        onExportRoute = { rec, pts, gpx, scale, range -> exportRoute(rec, pts, gpx, scale, range) },
                        activeTripId = live.trip.id,
                        onMerge = { picked, done ->
                            lifecycleScope.launch {
                                // Oldest first: each later drive is folded into the first one.
                                val merged = withContext(Dispatchers.IO) {
                                    picked.sortedBy { it.startMs }.reduce { acc, next -> recordings.mergeTrips(acc, next) }
                                }
                                LiveBus.tripsChanged()
                                done(merged)
                            }
                        },
                        onDelete = { trip, rec ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                // Deleting a drive removes both its totals and its recording.
                                if (rec != null) recordings.delete(rec.id, withTrip = true) else store.delete(trip.id)
                                LiveBus.tripsChanged()
                            }
                        },
                    )
                    Tab.Stats -> StatsScreen(store, live.tripsVersion, onExportTrips = ::exportTrips)
                    Tab.Car -> if (fuelOpen) {
                        FuelScreen(
                            fuel = fuel, settings = settings, tank = tank, version = fuelTick,
                            onBack = { fuelOpen = false },
                            onChanged = {
                                fuelTick++
                                LiveBus.update { it.copy(fuelPrice = settings.fuelPrice) }
                            },
                        )
                    } else {
                        com.abeernch.civicobd.ui.CarScreen(
                            live = live, car = car, tank = tank, settings = settings, starts = starts,
                            today = today, week = week, total = total,
                            onOpenFuel = { fuelOpen = true },
                            onServiceChanged = { fuelTick++ },
                        )
                    }
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
                        live = live,
                        onShareLog = ::shareLog,
                        onImport = { pickFiles.launch(arrayOf("text/*", "application/octet-stream", "*/*")) },
                    )
                }
            }
            if (introOn && splashOver) {
                com.abeernch.civicobd.ui.DriveIntro(
                    onReveal = { introRevealed = true },
                    onWakeAt = { wakeAtMs = it },
                    onDone = { introArmed = false; introRevealed = false },
                )
            }
            if (hud) HudScreen(live) { hud = false }
            if (inPip) com.abeernch.civicobd.ui.PipView(live)
            }
        }
    }

    // ---- Opened from a notification ----

    // Which tab to show, and which drive to open, when a notification is tapped.
    private var jump by mutableStateOf<Pair<Tab, Int>?>(null)
    private var openTripId by mutableStateOf<Long?>(null)
    private var jumpCount = 0

    private fun handleOpen(intent: Intent?) {
        val tab = intent?.getStringExtra(com.abeernch.civicobd.service.Alerts.EXTRA_TAB) ?: return
        val t = Tab.entries.firstOrNull { it.name == tab } ?: return
        if (intent.hasExtra(com.abeernch.civicobd.service.Alerts.EXTRA_TRIP)) {
            openTripId = intent.getLongExtra(com.abeernch.civicobd.service.Alerts.EXTRA_TRIP, -1)
        }
        jump = t to ++jumpCount
    }

    override fun onStart() {
        super.onStart()
        startServiceIfAllowed()
        checkLocationSwitch()
        // Back after five minutes or more: treat it as opening the app for a drive.
        if (stoppedAtMs > 0 && System.currentTimeMillis() - stoppedAtMs > 5 * 60_000L) introArmed = true
    }

    override fun onStop() {
        super.onStop()
        stoppedAtMs = System.currentTimeMillis()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("stoppedAtMs", if (stoppedAtMs > 0) stoppedAtMs else System.currentTimeMillis())
    }

    /** The GPS control, when location is off or not allowed: the switch in Settings, or the permission. */
    private fun openLocation() {
        if (LiveBus.state.value.locationOff) {
            settings.locOnByApp = true
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: Exception) {
            }
        } else {
            askPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    /** Shows the "Location is off" strip while the phone's location switch is off. */
    private fun checkLocationSwitch() {
        val on = try {
            getSystemService(android.location.LocationManager::class.java)
                ?.let { androidx.core.location.LocationManagerCompat.isLocationEnabled(it) } ?: true
        } catch (e: Exception) {
            true
        }
        LiveBus.update { it.copy(locationOff = !on) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
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
            name + ": " + "%.1f km, %d parameters imported".format(trip.distanceKm, channels)
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
            // Opening the app is the cue to switch Bluetooth on and look for the adapter.
            ObdService.wake(this)
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
