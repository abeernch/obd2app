package com.abeernch.civicobd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.abeernch.civicobd.ui.ProbeScreen
import com.abeernch.civicobd.ui.ProbeViewModel

class MainActivity : ComponentActivity() {

    private val probe: ProbeViewModel by viewModels()

    // Android 12+ needs explicit permission to talk to paired Bluetooth devices.
    private val askBluetooth =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) probe.loadDevices()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            probe.loadDevices()
        }

        setContent {
            val state by probe.state.collectAsState()
            ProbeScreen(
                state = state,
                onSelect = probe::select,
                onConnect = probe::connect,
                onDisconnect = probe::disconnect,
                onShare = { share(probe.report()) },
            )
        }
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Civic OBD probe report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share report"))
    }
}
