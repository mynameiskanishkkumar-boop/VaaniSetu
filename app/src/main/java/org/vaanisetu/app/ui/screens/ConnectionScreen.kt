package org.vaanisetu.app.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.vaanisetu.app.data.model.SocketErrorType
import org.vaanisetu.app.data.model.TestPacketStatus
import org.vaanisetu.app.data.model.TransportType
import org.vaanisetu.app.state.TransceiverCoordinator
import org.vaanisetu.app.transport.BluetoothDeviceItem
import org.vaanisetu.app.transport.BluetoothDiagnosticState
import org.vaanisetu.app.transport.TransportStatus
import org.vaanisetu.app.ui.theme.*

@Composable
fun ConnectionScreen(
    coordinator: TransceiverCoordinator,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeTransportType by coordinator.activeTransportType.collectAsState()
    val transportStatus by coordinator.activeTransport.status.collectAsState()
    val peerName by coordinator.activeTransport.connectedPeerName.collectAsState()
    val diag by coordinator.activeTransport.connectionDiagnostics.collectAsState()

    // Bluetooth state
    val isBtEnabled by coordinator.bluetoothTransport.isBluetoothEnabled.collectAsState()
    val isBtScanning by coordinator.bluetoothTransport.isScanning.collectAsState()
    val discoveredDevices by coordinator.bluetoothTransport.discoveredDevices.collectAsState()
    val pairedDevices by coordinator.bluetoothTransport.pairedDevices.collectAsState()
    val btDiag by coordinator.bluetoothTransport.bluetoothDiagnostics.collectAsState()

    var targetIpAddress by remember { mutableStateOf(diag.detectedHotspotIp ?: "192.168.43.1") }
    var targetPort by remember { mutableStateOf("8899") }
    var showLogs by remember { mutableStateOf(false) }

    // Activity Result Launcher for Discoverability Dialog (Part 3)
    val discoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        coordinator.bluetoothTransport.onDiscoverabilityResult(result.resultCode)
    }

    // Update default target IP if detectedHotspotIp becomes available
    LaunchedEffect(diag.detectedHotspotIp) {
        if (targetIpAddress.isEmpty() || targetIpAddress == "192.168.43.1") {
            diag.detectedHotspotIp?.let { targetIpAddress = it }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate900)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // --- TOP BAR ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Slate200)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "D2D Connection Manager",
                    color = Slate200,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Offline Zero-Cloud Transport (Wi-Fi / Bluetooth)",
                    color = Slate400,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- TRANSPORT TYPE SELECTOR ---
        Text(text = "SELECT TRANSPORT", color = Slate400, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = activeTransportType == TransportType.LOCAL_WIFI,
                onClick = { coordinator.setTransportType(TransportType.LOCAL_WIFI) },
                label = { Text("Local Wi-Fi", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f)
            )

            FilterChip(
                selected = activeTransportType == TransportType.BLUETOOTH,
                onClick = { coordinator.setTransportType(TransportType.BLUETOOTH) },
                label = { Text("Bluetooth", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f)
            )

            FilterChip(
                selected = activeTransportType == TransportType.EMBEDDED_BRIDGE,
                onClick = { coordinator.setTransportType(TransportType.EMBEDDED_BRIDGE) },
                label = { Text("Bridge", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(14.dp)) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- CONNECTION STATUS BANNER ---
        Card(
            colors = CardDefaults.cardColors(
                containerColor = when (transportStatus) {
                    TransportStatus.CONNECTED -> EmeraldGreen.copy(alpha = 0.15f)
                    TransportStatus.CONNECTING, TransportStatus.STARTING, TransportStatus.LISTENING -> AmberWarning.copy(alpha = 0.15f)
                    TransportStatus.ERROR -> CrimsonEmergency.copy(alpha = 0.15f)
                    else -> Slate800
                }
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (transportStatus) {
                        TransportStatus.CONNECTED -> Icons.Default.CheckCircle
                        TransportStatus.ERROR -> Icons.Default.Error
                        TransportStatus.LISTENING -> Icons.Default.Sensors
                        TransportStatus.CONNECTING -> Icons.Default.HourglassTop
                        else -> Icons.Default.Info
                    },
                    contentDescription = null,
                    tint = when (transportStatus) {
                        TransportStatus.CONNECTED -> EmeraldGreen
                        TransportStatus.ERROR -> CrimsonEmergency
                        TransportStatus.LISTENING, TransportStatus.CONNECTING, TransportStatus.STARTING -> AmberWarning
                        else -> Slate400
                    },
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Status: ${transportStatus.name}",
                        color = Slate200,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when (transportStatus) {
                            TransportStatus.CONNECTED -> "Peer: ${peerName ?: "Connected"}"
                            TransportStatus.LISTENING -> if (activeTransportType == TransportType.BLUETOOTH) "HOST ACTIVE - Waiting for VaaniSetu client..." else "Waiting for peer on ${diag.serverLocalSocketAddress ?: "0.0.0.0:$targetPort"}"
                            TransportStatus.CONNECTING -> if (activeTransportType == TransportType.BLUETOOTH) "Connecting to Bluetooth Device..." else "Connecting to $targetIpAddress:$targetPort..."
                            TransportStatus.ERROR -> btDiag.lastErrorDetail ?: diag.lastError?.title ?: "Connection Error"
                            else -> "No active peer connected"
                        },
                        color = if (transportStatus == TransportStatus.ERROR) CrimsonEmergency else Slate400,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // --- BLUETOOTH RFCOMM PANEL ---
        // ==========================================
        if (activeTransportType == TransportType.BLUETOOTH) {
            Text(text = "BLUETOOTH CLASSIC RFCOMM", color = Slate400, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            // Bluetooth Disabled Warning Card
            if (!isBtEnabled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AmberWarning.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BluetoothDisabled, contentDescription = null, tint = AmberWarning)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Bluetooth is OFF", color = AmberWarning, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Turn ON Bluetooth to discover and connect to nearby VaaniSetu devices without entering IP/Port.",
                            color = Slate300,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                try {
                                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                    enableBtIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(enableBtIntent)
                                } catch (_: Exception) {}
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Turn ON Bluetooth", color = Slate900, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // --- 1. HOST BLUETOOTH CARD (PHONE A) ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Phone A: Host Bluetooth (Server)",
                            color = CyanAccent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (transportStatus == TransportStatus.LISTENING) {
                            Text(text = "HOST ACTIVE", color = EmeraldGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (transportStatus == TransportStatus.LISTENING)
                            "VaaniSetu Host Active — Waiting for another VaaniSetu phone..."
                        else
                            "Tap to make this device discoverable for 300s and accept incoming RFCOMM connections.",
                        color = if (transportStatus == TransportStatus.LISTENING) EmeraldGreen else Slate400,
                        fontSize = 12.sp,
                        fontWeight = if (transportStatus == TransportStatus.LISTENING) FontWeight.Bold else FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            try {
                                discoverableLauncher.launch(coordinator.bluetoothTransport.requestDiscoverabilityIntent(300))
                            } catch (_: Exception) {}
                            scope.launch { coordinator.bluetoothTransport.startServer() }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (transportStatus == TransportStatus.LISTENING) EmeraldGreen else CyanPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Sensors, contentDescription = null, tint = Slate900)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (transportStatus == TransportStatus.LISTENING) "Host Active (Listening...)" else "Start / Host Bluetooth",
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                discoverableLauncher.launch(coordinator.bluetoothTransport.requestDiscoverabilityIntent(300))
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Make Phone Discoverable (300s)", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // --- 2. DISCOVER NEARBY DEVICES (PHONE B) ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Phone B: Discover & Connect (Client)",
                            color = CyanAccent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (isBtScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = CyanPrimary,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Scan for nearby Bluetooth devices and tap Connect on the Host.",
                        color = Slate400,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { coordinator.bluetoothTransport.startDiscovery() },
                        enabled = !isBtScanning,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Slate900)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isBtScanning) "Scanning Nearby Devices..." else "Scan Nearby Devices",
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // --- ERROR & RETRY BANNER ---
            if (transportStatus == TransportStatus.ERROR) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = CrimsonEmergency.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CrimsonEmergency)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Connection Failed",
                                color = CrimsonEmergency,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = btDiag.lastErrorDetail ?: "Bluetooth connection timed out. Please retry.",
                            color = Slate300,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { scope.launch { coordinator.bluetoothTransport.retryLastConnection() } },
                            colors = ButtonDefaults.buttonColors(containerColor = CrimsonEmergency),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = PureWhite)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Connection", color = PureWhite, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // --- 3. PAIRED DEVICES SECTION (PART 5) ---
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PAIRED DEVICES (${pairedDevices.size})",
                    color = Slate400,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = { coordinator.bluetoothTransport.refreshPairedDevices() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Paired", tint = CyanPrimary, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (pairedDevices.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.padding(12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No paired Bluetooth devices. Pair in Android Settings or discover nearby.",
                            color = Slate400,
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                pairedDevices.forEach { device ->
                    BluetoothDeviceCard(
                        device = device,
                        isConnecting = transportStatus == TransportStatus.CONNECTING,
                        onConnect = {
                            scope.launch { coordinator.bluetoothTransport.connectToDevice(device) }
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // --- 4. DISCOVERED NEARBY DEVICES SECTION (PART 4 - NO FILTERING) ---
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "NEARBY DISCOVERED DEVICES (${discoveredDevices.size})",
                color = Slate400,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (discoveredDevices.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.padding(16.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isBtScanning) "Scanning for nearby devices..." else "No nearby devices found yet. Tap 'Scan Nearby Devices' above.",
                            color = Slate400,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                discoveredDevices.forEach { device ->
                    BluetoothDeviceCard(
                        device = device,
                        isConnecting = transportStatus == TransportStatus.CONNECTING,
                        onConnect = {
                            scope.launch { coordinator.bluetoothTransport.connectToDevice(device) }
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // --- 5. PART 2: BLUETOOTH LIVE DIAGNOSTIC CARD ---
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "BLUETOOTH LIVE DIAGNOSTICS", color = CyanAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = {
                                coordinator.bluetoothTransport.refreshScanMode()
                                coordinator.bluetoothTransport.refreshPairedDevices()
                            },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Diag", tint = CyanPrimary, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    DiagnosticRow("Bluetooth enabled", if (btDiag.isBluetoothEnabled) "YES" else "NO", if (btDiag.isBluetoothEnabled) EmeraldGreen else CrimsonEmergency)
                    DiagnosticRow("BLUETOOTH_CONNECT", if (btDiag.connectPermissionGranted) "GRANTED" else "DENIED", if (btDiag.connectPermissionGranted) EmeraldGreen else CrimsonEmergency)
                    DiagnosticRow("BLUETOOTH_SCAN", if (btDiag.scanPermissionGranted) "GRANTED" else "DENIED", if (btDiag.scanPermissionGranted) EmeraldGreen else CrimsonEmergency)
                    DiagnosticRow("BLUETOOTH_ADVERTISE", if (btDiag.advertisePermissionGranted) "GRANTED" else "DENIED", if (btDiag.advertisePermissionGranted) EmeraldGreen else CrimsonEmergency)
                    DiagnosticRow("Device discoverable", btDiag.isDiscoverable, if (btDiag.isDiscoverable.startsWith("YES")) EmeraldGreen else AmberWarning)
                    DiagnosticRow("Discovery active", if (btDiag.isDiscoveryActive) "YES" else "NO", if (btDiag.isDiscoveryActive) CyanPrimary else Slate300)
                    DiagnosticRow("Paired devices count", "${btDiag.pairedDevicesCount}", Slate300)
                    DiagnosticRow("Discovered devices count", "${btDiag.discoveredDevicesCount}", Slate300)
                    DiagnosticRow("Selected device", btDiag.selectedDeviceName ?: "None", Slate300)
                    DiagnosticRow("Selected address", btDiag.selectedDeviceAddress ?: "Not available", Slate300)
                    DiagnosticRow("Role", btDiag.role, CyanAccent)
                    DiagnosticRow("RFCOMM server", btDiag.rfcommServerStatus, if (btDiag.rfcommServerStatus == "LISTENING") EmeraldGreen else Slate300)
                    DiagnosticRow("Connection state", btDiag.connectionState.name, if (btDiag.connectionState == TransportStatus.CONNECTED) EmeraldGreen else AmberWarning)
                    DiagnosticRow("UUID", btDiag.uuidString, Slate400)
                    DiagnosticRow("Socket status", btDiag.socketStatus, if (btDiag.socketStatus == "CONNECTED") EmeraldGreen else if (btDiag.socketStatus == "FAILED") CrimsonEmergency else Slate300)
                    val err = btDiag.lastErrorDetail
                    if (err != null) {
                        DiagnosticRow("Last Error / Hint", err, CrimsonEmergency)
                    }
                }
            }

        } else if (activeTransportType == TransportType.LOCAL_WIFI) {
            // ==========================================
            // --- WI-FI TCP TRANSPORT PANEL ---
            // ==========================================
            Text(text = "LOCAL WI-FI (ZERO INTERNET)", color = Slate400, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            // --- SERVER CARD ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Phone A: Server (Hotspot / Host)",
                            color = CyanAccent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { coordinator.wifiTransport.refreshNetworkInterfaces() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh IPs", tint = CyanPrimary)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Discovered Local IPv4 Address:",
                        color = Slate400,
                        fontSize = 11.sp
                    )
                    Text(
                        text = diag.detectedLocalIp?.let { "$it (Port: ${diag.serverLocalPort ?: targetPort})" } ?: "Detecting network interface...",
                        color = if (diag.detectedLocalIp != null) EmeraldGreen else AmberWarning,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { scope.launch { coordinator.wifiTransport.startServer(targetPort.toIntOrNull() ?: 8899) } },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Sensors, contentDescription = null, tint = Slate900)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (transportStatus == TransportStatus.LISTENING) "Server Active (Listening)" else "Start Server (Listen)",
                            color = Slate900,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- CLIENT CARD ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Phone B: Client (Join Peer)",
                        color = CyanAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Enter Phone A's actual IP address shown above.",
                        color = Slate400,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = targetIpAddress,
                        onValueChange = { targetIpAddress = it },
                        label = { Text("Phone A Server IP (e.g. 192.168.43.1)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = targetPort,
                        onValueChange = { targetPort = it },
                        label = { Text("Port (1-65535, Default 8899)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = { scope.launch { coordinator.wifiTransport.connectToPeer(targetIpAddress, targetPort.toIntOrNull() ?: 8899) } },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Link, contentDescription = null, tint = Slate900)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connect to Peer", color = Slate900, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // ==========================================
            // --- EMBEDDED HARDWARE BRIDGE PANEL ---
            // ==========================================
            Text(text = "EMBEDDED HARDWARE BRIDGE (LORA / SERIAL / VHF)", color = Slate400, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Hardware Interface: USB-UART / LoRa Bridge",
                        color = CyanAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Baud Rate: 115200 bps | Framing: Magic 'VS' + CRC32",
                        color = Slate400,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { scope.launch { coordinator.embeddedBridgeTransport.startServer(targetPort.toIntOrNull() ?: 8899) } },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Sensors, contentDescription = null, tint = Slate900)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Bridge Listener", color = Slate900, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- PART 13: TEST CONNECTION PING BUTTON ---
        if (transportStatus == TransportStatus.CONNECTED) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = PurpleAccent.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "D2D LINK VERIFICATION (PART 13)",
                        color = PurpleAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Verify bidirectional link independently without invoking STT/TTS.",
                        color = Slate300,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { scope.launch { coordinator.activeTransport.sendTestPacket() } },
                        colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = PureWhite)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (activeTransportType == TransportType.BLUETOOTH)
                                "TEST BLUETOOTH CONNECTION (Send VAANISETU_BT_TEST)"
                            else
                                "Test Connection (Send VAANISETU_TEST)",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (activeTransportType == TransportType.BLUETOOTH && btDiag.pingStatus != "IDLE") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = btDiag.pingStatus,
                            color = if (btDiag.pingStatus.contains("PASS")) EmeraldGreen else if (btDiag.pingStatus.contains("FAIL")) CrimsonEmergency else AmberWarning,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    } else if (diag.testStatus != TestPacketStatus.IDLE) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Test Result: ",
                                color = Slate400,
                                fontSize = 12.sp
                            )
                            Text(
                                text = diag.testMessageDetails.ifEmpty { diag.testStatus.name },
                                color = when (diag.testStatus) {
                                    TestPacketStatus.CRC_OK -> EmeraldGreen
                                    TestPacketStatus.RX_OK, TestPacketStatus.TX_OK -> CyanPrimary
                                    TestPacketStatus.FAILED -> CrimsonEmergency
                                    else -> AmberWarning
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- DISCONNECT BUTTON ---
        if (transportStatus != TransportStatus.DISCONNECTED) {
            OutlinedButton(
                onClick = { scope.launch { coordinator.activeTransport.disconnect() } },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonEmergency),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Disconnect")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // --- LIVE LOGS EXPANDER ---
        TextButton(
            onClick = { showLogs = !showLogs },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = if (showLogs) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = Slate400
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (showLogs) "Hide Diagnostics Logs" else "Show Diagnostics Logs (${diag.logHistory.size} events)",
                color = Slate400,
                fontSize = 12.sp
            )
        }

        if (showLogs) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    if (diag.logHistory.isEmpty()) {
                        Text(text = "No connection events logged yet.", color = Slate400, fontSize = 11.sp)
                    } else {
                        diag.logHistory.takeLast(25).forEach { logLine ->
                            Text(
                                text = logLine,
                                color = when {
                                    logLine.contains("ERROR") || logLine.contains("FAILURE") || logLine.contains("FAILED") -> CrimsonEmergency
                                    logLine.contains("SUCCESS") || logLine.contains("ACCEPTED") || logLine.contains("PASS") || logLine.contains("CRC_OK") -> EmeraldGreen
                                    logLine.contains("START") || logLine.contains("BIND") || logLine.contains("LISTENING") -> CyanAccent
                                    else -> Slate300
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "$label:", color = Slate400, fontSize = 11.sp)
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun BluetoothDeviceCard(
    device: BluetoothDeviceItem,
    isConnecting: Boolean,
    onConnect: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Slate800),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = if (device.isPaired) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = if (device.isPaired) CyanPrimary else Slate400,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = device.name,
                            color = Slate200,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (device.isPaired) "PAIRED" else "FOUND",
                            color = if (device.isPaired) CyanPrimary else Slate400,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(if (device.isPaired) CyanPrimary.copy(alpha = 0.15f) else Slate700, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = device.address,
                        color = Slate400,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Button(
                onClick = onConnect,
                enabled = !isConnecting,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Slate900,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(text = "Connect", color = Slate900, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
