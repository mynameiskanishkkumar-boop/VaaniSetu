package org.vaanisetu.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.vaanisetu.app.state.TransceiverCoordinator
import org.vaanisetu.app.ui.screens.ConnectionScreen
import org.vaanisetu.app.ui.screens.DiagnosticsScreen
import org.vaanisetu.app.ui.screens.HomeScreen
import org.vaanisetu.app.ui.screens.LanguageScreen
import org.vaanisetu.app.ui.theme.VaaniSetuTheme

enum class Screen {
    HOME,
    CONNECTION,
    LANGUAGE,
    DIAGNOSTICS
}

class MainActivity : ComponentActivity() {

    private lateinit var coordinator: TransceiverCoordinator

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions updated, refresh Bluetooth state
        coordinator.bluetoothTransport.refreshPairedDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = TransceiverCoordinator(applicationContext)

        checkAndRequestPermissions()

        lifecycleScope.launch {
            coordinator.initializeDefaultModels()
        }

        setContent {
            VaaniSetuTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf(Screen.HOME) }

                    when (currentScreen) {
                        Screen.HOME -> HomeScreen(
                            coordinator = coordinator,
                            onNavigateToConnection = { currentScreen = Screen.CONNECTION },
                            onNavigateToLanguage = { currentScreen = Screen.LANGUAGE },
                            onNavigateToDiagnostics = { currentScreen = Screen.DIAGNOSTICS }
                        )
                        Screen.CONNECTION -> ConnectionScreen(
                            coordinator = coordinator,
                            onNavigateBack = { currentScreen = Screen.HOME }
                        )
                        Screen.LANGUAGE -> LanguageScreen(
                            coordinator = coordinator,
                            onNavigateBack = { currentScreen = Screen.HOME }
                        )
                        Screen.DIAGNOSTICS -> DiagnosticsScreen(
                            coordinator = coordinator,
                            onNavigateBack = { currentScreen = Screen.HOME }
                        )
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val ungranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
        }
    }
}
