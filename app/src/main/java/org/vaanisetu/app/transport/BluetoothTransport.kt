package org.vaanisetu.app.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.vaanisetu.app.data.model.ConnectionDiagnostics
import org.vaanisetu.app.data.model.MessagePriority
import org.vaanisetu.app.data.model.SocketErrorDetail
import org.vaanisetu.app.data.model.SocketErrorType
import org.vaanisetu.app.data.model.VaaniPacket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Discovered or paired Bluetooth device item.
 */
data class BluetoothDeviceItem(
    val name: String,
    val address: String,
    val isPaired: Boolean = false,
    val bondState: Int = BluetoothDevice.BOND_NONE
)

/**
 * Real-time diagnostic state for Bluetooth troubleshooting.
 */
data class BluetoothDiagnosticState(
    val isBluetoothEnabled: Boolean = false,
    val connectPermissionGranted: Boolean = false,
    val scanPermissionGranted: Boolean = false,
    val advertisePermissionGranted: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    val isDiscoverable: String = "UNKNOWN",
    val isDiscoveryActive: Boolean = false,
    val pairedDevicesCount: Int = 0,
    val discoveredDevicesCount: Int = 0,
    val selectedDeviceName: String? = null,
    val selectedDeviceAddress: String? = null,
    val role: String = "IDLE", // "HOST", "CLIENT", "IDLE"
    val rfcommServerStatus: String = "NOT LISTENING", // "LISTENING", "NOT LISTENING", "ERROR"
    val connectionState: TransportStatus = TransportStatus.DISCONNECTED,
    val uuidString: String = "fa87c0d0-afac-11de-8a39-0800200c9a66",
    val socketStatus: String = "IDLE", // "IDLE", "CREATED", "CONNECTING", "CONNECTED", "FAILED"
    val pingStatus: String = "IDLE", // "IDLE", "TESTING...", "Bluetooth transport test: PASS", "Bluetooth transport test: FAIL"
    val lastErrorDetail: String? = null
)

/**
 * Bluetooth Classic RFCOMM Transport using a single fixed, stable application UUID.
 * Implements standard Secure RFCOMM with Insecure Fallback (No reflection hacks).
 */
class BluetoothTransport(private val context: Context) : PeerTransport {

    companion object {
        private const val TAG = "VAANISETU_BT"

        // Single fixed, stable application UUID for VaaniSetu RFCOMM Classic connection
        val VAANISETU_BT_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
        private const val SERVICE_NAME = "VaaniSetu"
        private const val CONNECT_TIMEOUT_MS = 12000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    @Suppress("DEPRECATION")
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var secureServerSocket: BluetoothServerSocket? = null
    private var insecureServerSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var dataOutputStream: DataOutputStream? = null
    private var listeningJob: Job? = null
    private var lastTargetAddress: String? = null

    private val _status = MutableStateFlow(TransportStatus.DISCONNECTED)
    override val status: StateFlow<TransportStatus> = _status

    private val _connectedPeerName = MutableStateFlow<String?>(null)
    override val connectedPeerName: StateFlow<String?> = _connectedPeerName

    private val _receivedPackets = MutableSharedFlow<VaaniPacket>(extraBufferCapacity = 64)
    override val receivedPackets: SharedFlow<VaaniPacket> = _receivedPackets

    private val _connectionDiagnostics = MutableStateFlow(
        ConnectionDiagnostics(role = "BLUETOOTH", transportName = "Bluetooth RFCOMM")
    )
    override val connectionDiagnostics: StateFlow<ConnectionDiagnostics> = _connectionDiagnostics

    // Diagnostics StateFlow for dedicated UI inspection
    private val _bluetoothDiagnostics = MutableStateFlow(BluetoothDiagnosticState())
    val bluetoothDiagnostics: StateFlow<BluetoothDiagnosticState> = _bluetoothDiagnostics

    // Discovery & device state
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDeviceItem>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDeviceItem>> = _discoveredDevices

    private val _pairedDevices = MutableStateFlow<List<BluetoothDeviceItem>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDeviceItem>> = _pairedDevices

    private val _isBluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled

    private var receiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    if (device != null) {
                        val rawName = try { device.name } catch (_: Exception) { null }
                        val address = device.address ?: "00:00:00:00:00:00"
                        val displayName = if (!rawName.isNullOrBlank()) {
                            rawName
                        } else {
                            "Bluetooth Device (${address.takeLast(5).replace(":", "")})"
                        }

                        val isBonded = device.bondState == BluetoothDevice.BOND_BONDED
                        val item = BluetoothDeviceItem(
                            name = displayName,
                            address = address,
                            isPaired = isBonded,
                            bondState = device.bondState
                        )

                        Log.i(TAG, "[BT_DEVICE_FOUND] name=\"$displayName\" address=$address paired=$isBonded bondState=${device.bondState}")
                        Log.i(TAG, "[BT_DEVICE_NAME] $displayName")
                        Log.i(TAG, "[BT_DEVICE_ADDRESS] $address")
                        appendDiagnosticLog("[BT_DEVICE_FOUND] $displayName ($address)")

                        val currentList = _discoveredDevices.value
                        if (currentList.none { it.address.equals(address, ignoreCase = true) }) {
                            val updated = currentList + item
                            _discoveredDevices.value = updated
                            updateDiagnostics { it.copy(discoveredDevicesCount = updated.size) }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    Log.i(TAG, "[BT_DISCOVERY_START] Scanning for nearby Bluetooth devices...")
                    appendDiagnosticLog("[BT_DISCOVERY_START] Scanning nearby devices")
                    _isScanning.value = true
                    updateDiagnostics { it.copy(isDiscoveryActive = true) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.i(TAG, "[BT_DISCOVERY_FINISHED] Found ${_discoveredDevices.value.size} nearby devices")
                    appendDiagnosticLog("[BT_DISCOVERY_FINISHED] Scan complete (${_discoveredDevices.value.size} devices)")
                    _isScanning.value = false
                    updateDiagnostics { it.copy(isDiscoveryActive = false) }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val isEnabled = state == BluetoothAdapter.STATE_ON
                    _isBluetoothEnabled.value = isEnabled
                    Log.i(TAG, "[BT_ENABLED] state=$state isEnabled=$isEnabled")
                    appendDiagnosticLog("[BT_ENABLED] state=$state isEnabled=$isEnabled")
                    updateDiagnostics { it.copy(isBluetoothEnabled = isEnabled) }
                    if (isEnabled) {
                        refreshPairedDevices()
                        refreshScanMode()
                    }
                }
                BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> {
                    val scanMode = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.SCAN_MODE_NONE)
                    val modeStr = when (scanMode) {
                        BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "YES (SCAN_MODE_CONNECTABLE_DISCOVERABLE)"
                        BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "NO (SCAN_MODE_CONNECTABLE)"
                        BluetoothAdapter.SCAN_MODE_NONE -> "NO (SCAN_MODE_NONE)"
                        else -> "UNKNOWN ($scanMode)"
                    }
                    Log.i(TAG, "[BT_DISCOVERABILITY_RESULT] Scan mode changed to: $modeStr")
                    appendDiagnosticLog("[BT_DISCOVERABILITY_RESULT] Discoverable: $modeStr")
                    updateDiagnostics { it.copy(isDiscoverable = modeStr) }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val deviceName = try { device?.name ?: device?.address } catch (_: Exception) { "Device" }

                    when (bondState) {
                        BluetoothDevice.BOND_BONDING -> {
                            Log.i(TAG, "[BT_PAIRING_STARTED] Pairing with $deviceName")
                            appendDiagnosticLog("[BT_PAIRING_STARTED] Pairing with $deviceName")
                            updateDiagnostics { it.copy(lastErrorDetail = "Pairing in progress with $deviceName... Please accept prompt.") }
                        }
                        BluetoothDevice.BOND_BONDED -> {
                            Log.i(TAG, "[BT_PAIRING_SUCCESS] Paired successfully with $deviceName")
                            appendDiagnosticLog("[BT_PAIRING_SUCCESS] Paired with $deviceName")
                            updateDiagnostics { it.copy(lastErrorDetail = null) }
                            refreshPairedDevices()
                        }
                        BluetoothDevice.BOND_NONE -> {
                            if (prevBondState == BluetoothDevice.BOND_BONDING) {
                                Log.w(TAG, "[BT_PAIRING_FAILED] Pairing failed with $deviceName")
                                appendDiagnosticLog("[BT_PAIRING_FAILED] Pairing failed with $deviceName")
                                updateDiagnostics { it.copy(lastErrorDetail = "Pairing cancelled or rejected by $deviceName.") }
                            }
                            refreshPairedDevices()
                        }
                    }
                }
            }
        }
    }

    init {
        logAdapterAndPermissionStatus()
        registerReceiverIfNeeded()
        refreshPairedDevices()
        refreshScanMode()
        updateDiagnostics {
            it.copy(
                uuidString = VAANISETU_BT_UUID.toString()
            )
        }
    }

    private fun logAdapterAndPermissionStatus() {
        val adapterPresent = bluetoothAdapter != null
        val adapterEnabled = bluetoothAdapter?.isEnabled == true
        val hasPerms = checkHasBluetoothPermissions()

        val connectGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

        val scanGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true

        val advGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else true

        val locGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        Log.i(TAG, "[BT_ADAPTER] present=$adapterPresent enabled=$adapterEnabled")
        Log.i(TAG, "[BT_PERMISSION] granted=$hasPerms sdk=${Build.VERSION.SDK_INT} connect=$connectGranted scan=$scanGranted adv=$advGranted loc=$locGranted")
        Log.i(TAG, "[BT_ENABLED] $adapterEnabled")

        updateDiagnostics {
            it.copy(
                isBluetoothEnabled = adapterEnabled,
                connectPermissionGranted = connectGranted,
                scanPermissionGranted = scanGranted,
                advertisePermissionGranted = advGranted,
                locationPermissionGranted = locGranted
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshScanMode() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            updateDiagnostics { it.copy(isDiscoverable = "NO (Bluetooth OFF)") }
            return
        }
        try {
            val scanMode = adapter.scanMode
            val modeStr = when (scanMode) {
                BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "YES (SCAN_MODE_CONNECTABLE_DISCOVERABLE)"
                BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "NO (SCAN_MODE_CONNECTABLE)"
                BluetoothAdapter.SCAN_MODE_NONE -> "NO (SCAN_MODE_NONE)"
                else -> "UNKNOWN ($scanMode)"
            }
            updateDiagnostics { it.copy(isDiscoverable = modeStr) }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking scanMode: ${e.message}")
        }
    }

    fun checkHasBluetoothPermissions(): Boolean {
        val locationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connectGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val scanGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val advGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            connectGranted && scanGranted && advGranted
        } else {
            val btGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            val adminGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
            btGranted && adminGranted && locationGranted
        }
    }

    fun requestDiscoverabilityIntent(durationSeconds: Int = 300): Intent {
        Log.i(TAG, "[BT_HOST_START] Preparing Discoverability Intent for ${durationSeconds}s")
        Log.i(TAG, "[BT_DISCOVERABILITY_REQUEST] duration=${durationSeconds}s")
        appendDiagnosticLog("[BT_DISCOVERABILITY_REQUEST] duration=${durationSeconds}s")
        return Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSeconds)
        }
    }

    fun onDiscoverabilityResult(resultCode: Int) {
        val success = resultCode > 0
        Log.i(TAG, "[BT_DISCOVERABILITY_RESULT] resultCode=$resultCode success=$success")
        appendDiagnosticLog("[BT_DISCOVERABILITY_RESULT] Result: ${if (success) "${resultCode}s discoverable" else "Rejected/Cancelled"}")
        refreshScanMode()
    }

    private fun registerReceiverIfNeeded() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
            try {
                context.registerReceiver(bluetoothReceiver, filter)
                receiverRegistered = true
                Log.d(TAG, "Bluetooth BroadcastReceiver registered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register broadcast receiver: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _isBluetoothEnabled.value = false
            _pairedDevices.value = emptyList()
            updateDiagnostics { it.copy(pairedDevicesCount = 0) }
            return
        }

        _isBluetoothEnabled.value = true
        try {
            val bonded = adapter.bondedDevices
            val items = bonded?.map { device ->
                val name = try { device.name } catch (_: Exception) { null } ?: "Device (${device.address.takeLast(5).replace(":", "")})"
                BluetoothDeviceItem(
                    name = name,
                    address = device.address,
                    isPaired = true,
                    bondState = device.bondState
                )
            } ?: emptyList()
            _pairedDevices.value = items
            Log.i(TAG, "[BT_PAIRED_REFRESH] ${items.size} paired devices found")
            updateDiagnostics { it.copy(pairedDevicesCount = items.size) }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading paired devices: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(): Boolean {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "[BT_ERROR] Cannot start discovery: Bluetooth is disabled or unavailable")
            appendDiagnosticLog("[BT_ERROR] Bluetooth disabled or unavailable")
            return false
        }

        registerReceiverIfNeeded()
        refreshPairedDevices()
        refreshScanMode()

        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            _discoveredDevices.value = emptyList()
            val started = adapter.startDiscovery()
            _isScanning.value = started
            Log.i(TAG, "[BT_DISCOVERY_START] result=$started")
            appendDiagnosticLog("[BT_DISCOVERY_START] result=$started")
            updateDiagnostics { it.copy(isDiscoveryActive = started, discoveredDevicesCount = 0) }
            return started
        } catch (e: Exception) {
            Log.e(TAG, "[BT_ERROR] Discovery exception: ${e.message}", e)
            appendDiagnosticLog("[BT_ERROR] Discovery exception: ${e.message}")
            _isScanning.value = false
            updateDiagnostics { it.copy(isDiscoveryActive = false) }
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        try {
            val adapter = bluetoothAdapter
            if (adapter?.isDiscovering == true) {
                adapter.cancelDiscovery()
            }
            _isScanning.value = false
            Log.i(TAG, "[BT_DISCOVERY_CANCEL] Discovery cancelled")
            appendDiagnosticLog("[BT_DISCOVERY_CANCEL] Discovery cancelled")
            updateDiagnostics { it.copy(isDiscoveryActive = false) }
        } catch (e: Exception) {
            Log.w(TAG, "stopDiscovery notice: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startServer(port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            val err = "Bluetooth is disabled or unavailable"
            Log.e(TAG, "[BT_HOST_START] [BT_SERVER_START] [BT_ERROR] $err")
            appendDiagnosticLog("[BT_HOST_START] [BT_SERVER_START] [BT_ERROR] $err")
            return@withContext Result.failure(IllegalStateException(err))
        }

        disconnect()

        try {
            Log.i(TAG, "[BT_HOST_START] Starting Host RFCOMM Server")
            Log.i(TAG, "[BT_UUID] role=HOST uuid=$VAANISETU_BT_UUID")
            Log.i(TAG, "[BT_SERVER_START] ServiceRecord=\"$SERVICE_NAME\", UUID=$VAANISETU_BT_UUID")
            appendDiagnosticLog("[BT_SERVER_START] ServiceRecord=\"$SERVICE_NAME\" UUID=$VAANISETU_BT_UUID")

            // Dual RFCOMM Server Setup: Listen for both Secure and Insecure client handshakes
            secureServerSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, VAANISETU_BT_UUID)
            insecureServerSocket = try {
                adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, VAANISETU_BT_UUID)
            } catch (e: Exception) {
                Log.w(TAG, "Insecure server socket notice: ${e.message}")
                null
            }

            _status.value = TransportStatus.LISTENING
            Log.i(TAG, "[BT_SERVER_LISTENING] ServerSocket listening on UUID=$VAANISETU_BT_UUID")
            appendDiagnosticLog("[BT_SERVER_LISTENING] ServerSocket listening on UUID=$VAANISETU_BT_UUID")

            _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                role = "SERVER (Bluetooth Host)",
                acceptState = "LISTENING_RFCOMM",
                serverIsBound = true,
                serverIsClosed = false
            )

            updateDiagnostics {
                it.copy(
                    role = "HOST",
                    rfcommServerStatus = "LISTENING",
                    connectionState = TransportStatus.LISTENING,
                    socketStatus = "IDLE",
                    lastErrorDetail = null
                )
            }

            // Launch listener loop handling both server sockets
            listeningJob = scope.launch {
                val sSocket = secureServerSocket
                val inSocket = insecureServerSocket

                // Secure accept worker
                launch {
                    while (isActive && sSocket != null) {
                        try {
                            Log.i(TAG, "[BT_SERVER_ACCEPT_WAIT] [SECURE] Waiting for incoming client connection...")
                            appendDiagnosticLog("[BT_SERVER_ACCEPT_WAIT] Waiting for client...")
                            val socket = sSocket.accept() ?: break

                            val remoteName = try { socket.remoteDevice?.name ?: socket.remoteDevice?.address } catch (_: Exception) { "Peer" }
                            val remoteAddress = try { socket.remoteDevice?.address } catch (_: Exception) { "Unknown" }
                            Log.i(TAG, "[BT_SERVER_ACCEPT_SUCCESS] Accepted Secure connection from $remoteName ($remoteAddress)")
                            appendDiagnosticLog("[BT_SERVER_ACCEPT_SUCCESS] Accepted from $remoteName ($remoteAddress)")

                            handleConnectedSocket(socket)
                        } catch (e: IOException) {
                            if (!isActive || secureServerSocket == null) break
                            Log.w(TAG, "[BT_SOCKET_ERROR] Secure server accept IOException: ${e.message}")
                            delay(500)
                        } catch (e: Exception) {
                            if (isActive && _status.value != TransportStatus.CONNECTED) {
                                Log.e(TAG, "[BT_SOCKET_ERROR] Secure accept error: ${e.message}", e)
                                delay(500)
                            }
                        }
                    }
                }

                // Insecure accept worker
                if (inSocket != null) {
                    launch {
                        while (isActive && inSocket != null) {
                            try {
                                Log.i(TAG, "[BT_SERVER_ACCEPT_WAIT] [INSECURE] Waiting for incoming client connection...")
                                val socket = inSocket.accept() ?: break

                                val remoteName = try { socket.remoteDevice?.name ?: socket.remoteDevice?.address } catch (_: Exception) { "Peer" }
                                val remoteAddress = try { socket.remoteDevice?.address } catch (_: Exception) { "Unknown" }
                                Log.i(TAG, "[BT_SERVER_ACCEPT_SUCCESS] Accepted Insecure connection from $remoteName ($remoteAddress)")
                                appendDiagnosticLog("[BT_SERVER_ACCEPT_SUCCESS] Accepted Insecure from $remoteName ($remoteAddress)")

                                handleConnectedSocket(socket)
                            } catch (e: IOException) {
                                if (!isActive || insecureServerSocket == null) break
                                delay(500)
                            } catch (e: Exception) {
                                if (isActive && _status.value != TransportStatus.CONNECTED) {
                                    delay(500)
                                }
                            }
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "[BT_SERVER_START] [BT_ERROR] Failed to start server: ${e.message}", e)
            appendDiagnosticLog("[BT_SERVER_START] [BT_ERROR] ${e.message}")
            _status.value = TransportStatus.ERROR
            updateDiagnostics {
                it.copy(
                    rfcommServerStatus = "ERROR",
                    connectionState = TransportStatus.ERROR,
                    lastErrorDetail = "Server Start Failed: ${e.message}"
                )
            }
            _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                lastError = SocketErrorDetail(
                    errorType = SocketErrorType.GENERIC_SOCKET_ERROR,
                    title = "Bluetooth Server Start Failed",
                    exceptionClass = e.javaClass.name,
                    exceptionMessage = e.message ?: "Could not open RFCOMM ServerSocket",
                    rootCause = e.cause?.message,
                    diagnosticHint = "Ensure Bluetooth is enabled and discoverability is granted."
                )
            )
            Result.failure(e)
        }
    }

    suspend fun connectToDevice(deviceItem: BluetoothDeviceItem): Result<Unit> {
        Log.i(TAG, "[BT_CONNECT_START] Selected target device: ${deviceItem.name} (${deviceItem.address})")
        updateDiagnostics {
            it.copy(
                selectedDeviceName = deviceItem.name,
                selectedDeviceAddress = deviceItem.address
            )
        }
        return connectToPeer(deviceItem.address, 0)
    }

    suspend fun retryLastConnection(): Result<Unit> {
        val target = lastTargetAddress
        return if (!target.isNullOrBlank()) {
            Log.i(TAG, "[BT_RECONNECT] Retrying connection to target: $target")
            appendDiagnosticLog("[BT_RECONNECT] Retrying connection to $target")
            connectToPeer(target, 0)
        } else {
            Result.failure(IllegalStateException("No previous target device to reconnect to"))
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connectToPeer(targetAddress: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        lastTargetAddress = targetAddress
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            val err = "Bluetooth is disabled or unavailable"
            Log.e(TAG, "[BT_CONNECT_FAILED] [BT_ERROR] $err")
            appendDiagnosticLog("[BT_CONNECT_FAILED] [BT_ERROR] $err")
            return@withContext Result.failure(IllegalStateException(err))
        }

        // Cancel discovery and wait 150ms for radio stack to settle before RFCOMM connect
        stopDiscovery()
        delay(150)
        disconnect()

        Log.i(TAG, "[BT_UUID] role=CLIENT uuid=$VAANISETU_BT_UUID")
        Log.i(TAG, "[BT_DISCOVERY_CANCEL] Discovery cancelled before connect")
        Log.i(TAG, "[BT_CONNECT_START] Target MAC=$targetAddress UUID=$VAANISETU_BT_UUID timeout=${CONNECT_TIMEOUT_MS}ms")
        appendDiagnosticLog("[BT_CONNECT_START] Target MAC=$targetAddress timeout=${CONNECT_TIMEOUT_MS}ms")

        _status.value = TransportStatus.CONNECTING
        updateDiagnostics {
            it.copy(
                role = "CLIENT",
                connectionState = TransportStatus.CONNECTING,
                socketStatus = "CONNECTING",
                selectedDeviceAddress = targetAddress,
                lastErrorDetail = null
            )
        }

        _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
            role = "CLIENT (Phone B)",
            targetIp = targetAddress,
            socketIsConnected = false,
            socketIsClosed = false
        )

        var establishedSocket: BluetoothSocket? = null
        var lastException: Exception? = null

        try {
            val remoteDevice = adapter.getRemoteDevice(targetAddress)
            val devName = try { remoteDevice.name ?: targetAddress } catch (_: Exception) { targetAddress }
            updateDiagnostics { it.copy(selectedDeviceName = devName) }

            // Stage 1: Standard Secure RFCOMM (Part 7)
            try {
                Log.d(TAG, "[BT_CONNECT_START] Stage 1: Attempting Secure RFCOMM Socket...")
                updateDiagnostics { it.copy(socketStatus = "CREATED (Secure)") }
                val s = remoteDevice.createRfcommSocketToServiceRecord(VAANISETU_BT_UUID)
                withTimeout(CONNECT_TIMEOUT_MS) {
                    s.connect()
                }
                establishedSocket = s
                Log.i(TAG, "[BT_CONNECT_SUCCESS] [BT_SOCKET_CONNECTED] Stage 1 Secure RFCOMM connected successfully")
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "[BT_SECURE_CONNECT_FAILED] Secure RFCOMM failed: ${e.message} (class=${e.javaClass.name})")
                appendDiagnosticLog("[BT_SECURE_CONNECT_FAILED] Secure failed: ${e.message}")

                if (remoteDevice.bondState == BluetoothDevice.BOND_BONDING) {
                    Log.i(TAG, "[BT_PAIRING_REQUIRED] Bluetooth pairing required for $devName")
                    updateDiagnostics { it.copy(lastErrorDetail = "Bluetooth pairing required. Please accept the Android pairing request.") }
                }
            }

            // Stage 2: Insecure RFCOMM Fallback (Part 8 - No reflection)
            if (establishedSocket == null || !establishedSocket.isConnected) {
                try {
                    establishedSocket?.close()
                } catch (_: Exception) {}

                try {
                    Log.i(TAG, "[BT_INSECURE_FALLBACK_START] Stage 2: Attempting Insecure RFCOMM fallback...")
                    appendDiagnosticLog("[BT_INSECURE_FALLBACK_START] Trying Insecure RFCOMM fallback...")
                    updateDiagnostics { it.copy(socketStatus = "CREATED (Insecure Fallback)") }

                    val s = remoteDevice.createInsecureRfcommSocketToServiceRecord(VAANISETU_BT_UUID)
                    withTimeout(CONNECT_TIMEOUT_MS) {
                        s.connect()
                    }
                    establishedSocket = s
                    Log.i(TAG, "[BT_INSECURE_CONNECT_SUCCESS] [BT_SOCKET_CONNECTED] Insecure RFCOMM connected successfully")
                    appendDiagnosticLog("[BT_INSECURE_CONNECT_SUCCESS] Insecure connection established")
                } catch (e: Exception) {
                    lastException = e
                    Log.e(TAG, "[BT_INSECURE_CONNECT_FAILED] RFCOMM connect failed", e)
                    appendDiagnosticLog("[BT_INSECURE_CONNECT_FAILED] Failed: ${e.message}")
                }
            }

            if (establishedSocket != null && establishedSocket.isConnected) {
                val peerName = try { remoteDevice.name ?: targetAddress } catch (_: Exception) { targetAddress }
                Log.i(TAG, "[BT_CONNECT_SUCCESS] [BT_SOCKET_CONNECTED] Connected to $peerName ($targetAddress)")
                appendDiagnosticLog("[BT_CONNECT_SUCCESS] Connected to $peerName ($targetAddress)")
                updateDiagnostics {
                    it.copy(
                        socketStatus = "CONNECTED",
                        connectionState = TransportStatus.CONNECTED,
                        lastErrorDetail = null
                    )
                }
                handleConnectedSocket(establishedSocket)
                Result.success(Unit)
            } else {
                val finalErr = lastException ?: IOException("Failed to establish RFCOMM socket to $targetAddress")
                throw finalErr
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "[BT_CONNECT_FAILED] RFCOMM connect failed: class=${e.javaClass.name} msg=${e.message} target=$targetAddress role=CLIENT uuid=$VAANISETU_BT_UUID",
                e
            )
            appendDiagnosticLog("[BT_CONNECT_FAILED] Exception: ${e.javaClass.simpleName}: ${e.message}")
            _status.value = TransportStatus.ERROR
            val humanReason = when {
                e is TimeoutCancellationException -> "Bluetooth connection timed out (12s). Ensure Host has tapped 'Start / Host' and is nearby."
                e.message?.contains("read failed", ignoreCase = true) == true -> "Connection rejected or pairing failed. Please accept pairing prompt."
                else -> e.message ?: "RFCOMM connection failed"
            }

            updateDiagnostics {
                it.copy(
                    socketStatus = "FAILED",
                    connectionState = TransportStatus.ERROR,
                    lastErrorDetail = humanReason
                )
            }

            _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                lastError = SocketErrorDetail(
                    errorType = SocketErrorType.CONNECTION_TIMED_OUT,
                    title = "Bluetooth Connection Failed",
                    exceptionClass = e.javaClass.name,
                    exceptionMessage = e.message ?: "RFCOMM connection failed",
                    rootCause = e.cause?.message,
                    destinationIp = targetAddress,
                    timeoutMs = CONNECT_TIMEOUT_MS.toInt(),
                    diagnosticHint = humanReason
                )
            )
            try {
                establishedSocket?.close()
            } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectedSocket(socket: BluetoothSocket) {
        activeSocket = socket
        dataOutputStream = DataOutputStream(socket.outputStream)
        val name = try { socket.remoteDevice?.name ?: socket.remoteDevice?.address } catch (_: Exception) { "Bluetooth Peer" }
        _connectedPeerName.value = name
        _status.value = TransportStatus.CONNECTED

        Log.i(TAG, "[BT_SOCKET_CONNECTED] Active link ready: peer=$name (${socket.remoteDevice?.address})")
        appendDiagnosticLog("[BT_SOCKET_CONNECTED] Active link ready: peer=$name")

        _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
            socketIsConnected = true,
            socketIsClosed = false,
            clientRemoteSocketAddress = socket.remoteDevice?.address ?: name
        )

        updateDiagnostics {
            it.copy(
                connectionState = TransportStatus.CONNECTED,
                socketStatus = "CONNECTED",
                selectedDeviceName = name,
                selectedDeviceAddress = socket.remoteDevice?.address
            )
        }

        scope.launch {
            val dis = DataInputStream(socket.inputStream)
            try {
                val headerBuffer = ByteArray(VaaniPacket.PRE_PAYLOAD_HEADER_SIZE)
                while (isActive && socket.isConnected) {
                    // Read 24-byte pre-payload header
                    dis.readFully(headerBuffer)

                    // Verify magic bytes
                    if (headerBuffer[0] != VaaniPacket.MAGIC_BYTE_1 || headerBuffer[1] != VaaniPacket.MAGIC_BYTE_2) {
                        continue
                    }

                    // Peek payload length from header bytes 22-23
                    val payloadLength = ((headerBuffer[22].toInt() and 0xFF) shl 8) or (headerBuffer[23].toInt() and 0xFF)
                    val fullPacketBytes = ByteArray(VaaniPacket.PRE_PAYLOAD_HEADER_SIZE + payloadLength + VaaniPacket.CRC_SIZE)
                    System.arraycopy(headerBuffer, 0, fullPacketBytes, 0, VaaniPacket.PRE_PAYLOAD_HEADER_SIZE)

                    // Read payload + 4 bytes CRC
                    dis.readFully(fullPacketBytes, VaaniPacket.PRE_PAYLOAD_HEADER_SIZE, payloadLength + VaaniPacket.CRC_SIZE)

                    // Deserialize & verify CRC32
                    val packet = VaaniPacket.deserialize(fullPacketBytes)
                    Log.i(TAG, "[BT_RX] seq=${packet.sequenceNumber} lang=${packet.languageCode} bytes=${fullPacketBytes.size} text=\"${packet.textPayload}\"")
                    appendDiagnosticLog("[BT_RX] seq=${packet.sequenceNumber} [${packet.languageCode}]: \"${packet.textPayload}\"")

                    // Handle Ping Test Packets (Part 13)
                    if (packet.textPayload == "VAANISETU_BT_TEST" || packet.textPayload == "VAANISETU_TEST") {
                        Log.i(TAG, "[BT_PING] Received VAANISETU_BT_TEST -> Replying VAANISETU_BT_ACK")
                        val ackPacket = VaaniPacket(
                            priority = MessagePriority.NORMAL,
                            sequenceNumber = packet.sequenceNumber,
                            languageCode = "en",
                            textPayload = "VAANISETU_BT_ACK"
                        )
                        sendPacket(ackPacket)
                    } else if (packet.textPayload == "VAANISETU_BT_ACK" || packet.textPayload == "VAANISETU_TEST_ACK") {
                        Log.i(TAG, "[BT_PING] Received VAANISETU_BT_ACK -> Bluetooth transport test: PASS")
                        appendDiagnosticLog("[BT_PING] Bluetooth transport test: PASS")
                        updateDiagnostics { it.copy(pingStatus = "Bluetooth transport test: PASS") }
                    }

                    _receivedPackets.emit(packet)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "[BT_DISCONNECTED] [BT_SOCKET_ERROR] Socket disconnected: ${e.message}")
                    appendDiagnosticLog("[BT_DISCONNECTED] Socket disconnected: ${e.message}")
                }
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {}
                activeSocket = null
                dataOutputStream = null
                _connectedPeerName.value = null

                // If ServerSocket is still active, return to LISTENING state for next client
                if (secureServerSocket != null || insecureServerSocket != null) {
                    _status.value = TransportStatus.LISTENING
                    Log.i(TAG, "[BT_SERVER_LISTENING] Returned to Host Listening state")
                    appendDiagnosticLog("[BT_SERVER_LISTENING] Host listening for next client")
                    updateDiagnostics {
                        it.copy(
                            connectionState = TransportStatus.LISTENING,
                            socketStatus = "IDLE",
                            rfcommServerStatus = "LISTENING"
                        )
                    }
                } else {
                    _status.value = TransportStatus.DISCONNECTED
                    updateDiagnostics {
                        it.copy(
                            connectionState = TransportStatus.DISCONNECTED,
                            socketStatus = "IDLE",
                            rfcommServerStatus = "NOT LISTENING"
                        )
                    }
                }

                _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                    socketIsConnected = false,
                    socketIsClosed = true
                )
            }
        }
    }

    override suspend fun sendPacket(packet: VaaniPacket): Result<Unit> = withContext(Dispatchers.IO) {
        val dos = dataOutputStream
        if (dos == null || activeSocket?.isConnected != true) {
            val err = "Bluetooth socket not connected"
            Log.e(TAG, "[BT_SOCKET_ERROR] [BT_TX_FAILED] sendPacket failed: $err")
            appendDiagnosticLog("[BT_TX_FAILED] Socket not connected")
            return@withContext Result.failure(IllegalStateException(err))
        }

        try {
            val bytes = VaaniPacket.serialize(packet)
            dos.write(bytes)
            dos.flush()
            Log.i(TAG, "[BT_TX] seq=${packet.sequenceNumber} lang=${packet.languageCode} bytes=${bytes.size} text=\"${packet.textPayload}\"")
            appendDiagnosticLog("[BT_TX] seq=${packet.sequenceNumber} [${packet.languageCode}]: \"${packet.textPayload}\"")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "[BT_SOCKET_ERROR] [BT_TX_FAILED] Failed to send packet: ${e.message}", e)
            appendDiagnosticLog("[BT_TX_FAILED] Error: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun sendTestPacket(): Result<Unit> = withContext(Dispatchers.IO) {
        updateDiagnostics { it.copy(pingStatus = "TESTING...") }
        val testPacket = VaaniPacket(
            priority = MessagePriority.NORMAL,
            sequenceNumber = System.currentTimeMillis() % 100000,
            languageCode = "en",
            textPayload = "VAANISETU_BT_TEST"
        )
        val res = sendPacket(testPacket)
        if (res.isFailure) {
            updateDiagnostics { it.copy(pingStatus = "Bluetooth transport test: FAIL") }
        }
        res
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            listeningJob?.cancel()
            listeningJob = null

            try {
                dataOutputStream?.close()
                activeSocket?.close()
                secureServerSocket?.close()
                insecureServerSocket?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Disconnect notice: ${e.message}")
            } finally {
                activeSocket = null
                secureServerSocket = null
                insecureServerSocket = null
                dataOutputStream = null
                _status.value = TransportStatus.DISCONNECTED
                _connectedPeerName.value = null
                _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                    socketIsConnected = false,
                    socketIsClosed = true,
                    serverIsBound = false,
                    serverIsClosed = true
                )
                updateDiagnostics {
                    it.copy(
                        connectionState = TransportStatus.DISCONNECTED,
                        socketStatus = "IDLE",
                        rfcommServerStatus = "NOT LISTENING",
                        role = "IDLE"
                    )
                }
                Log.i(TAG, "[BT_DISCONNECTED] Closed all Bluetooth sockets")
                appendDiagnosticLog("[BT_DISCONNECTED] Closed all Bluetooth sockets")
            }
        }
    }

    private inline fun updateDiagnostics(block: (BluetoothDiagnosticState) -> BluetoothDiagnosticState) {
        _bluetoothDiagnostics.value = block(_bluetoothDiagnostics.value)
    }

    private fun appendDiagnosticLog(log: String) {
        val current = _connectionDiagnostics.value.logHistory
        val updated = (current + log).takeLast(50)
        _connectionDiagnostics.value = _connectionDiagnostics.value.copy(logHistory = updated)
    }

    fun cleanup() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }
    }
}
