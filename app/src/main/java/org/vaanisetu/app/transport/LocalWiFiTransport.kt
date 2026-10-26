package org.vaanisetu.app.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.vaanisetu.app.data.model.*
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.util.Collections

/**
 * High-speed local Wi-Fi / Hotspot TCP Socket transport with NSD auto-discovery
 * and full real-time diagnostics.
 * Operates 100% offline without any Internet connection.
 */
class LocalWiFiTransport(private val context: Context) : PeerTransport {

    companion object {
        private const val TAG = "VAANISETU_D2D"
        private const val SERVICE_TYPE = "_vaanisetu._tcp."
        private const val SERVICE_NAME = "VaaniSetuPeer"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val MAX_LOG_HISTORY = 50
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var dataOutputStream: DataOutputStream? = null
    private var listeningJob: Job? = null
    private var readingJob: Job? = null
    private var nsdManager: NsdManager? = null

    private val _status = MutableStateFlow(TransportStatus.DISCONNECTED)
    override val status: StateFlow<TransportStatus> = _status

    private val _connectedPeerName = MutableStateFlow<String?>(null)
    override val connectedPeerName: StateFlow<String?> = _connectedPeerName

    private val _receivedPackets = MutableSharedFlow<VaaniPacket>(extraBufferCapacity = 64)
    override val receivedPackets: SharedFlow<VaaniPacket> = _receivedPackets

    private val _connectionDiagnostics = MutableStateFlow(ConnectionDiagnostics())
    override val connectionDiagnostics: StateFlow<ConnectionDiagnostics> = _connectionDiagnostics

    init {
        // Enumerate local interfaces at startup so UI can display server's real IP immediately
        refreshNetworkInterfaces()
    }

    private fun logEvent(event: String, message: String) {
        val formatted = "[$TAG] $event: $message"
        Log.i(TAG, formatted)
        synchronized(this) {
            val currentLogs = _connectionDiagnostics.value.logHistory.toMutableList()
            if (currentLogs.size >= MAX_LOG_HISTORY) {
                currentLogs.removeAt(0)
            }
            currentLogs.add("[$event] $message")
            _connectionDiagnostics.value = _connectionDiagnostics.value.copy(logHistory = currentLogs)
        }
    }

    /**
     * Enumerates all physical network interfaces and extracts valid IPv4 addresses.
     */
    fun refreshNetworkInterfaces(): List<NetworkInterfaceInfo> {
        val interfaces = mutableListOf<NetworkInterfaceInfo>()
        var bestLocalIp: String? = null
        var bestHotspotIp: String? = null

        try {
            val networkInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in networkInterfaces) {
                val addrs = mutableListOf<String>()
                val inetAddrs = Collections.list(intf.inetAddresses)
                for (addr in inetAddrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val hostAddr = addr.hostAddress ?: continue
                        addrs.add(hostAddr)
                        if (hostAddr.startsWith("192.168.43.")) {
                            bestHotspotIp = hostAddr
                        }
                        if (bestLocalIp == null || intf.name.startsWith("wlan") || intf.name.startsWith("ap")) {
                            bestLocalIp = hostAddr
                        }
                    }
                }
                interfaces.add(
                    NetworkInterfaceInfo(
                        name = intf.name,
                        displayName = intf.displayName,
                        isUp = intf.isUp,
                        isLoopback = intf.isLoopback,
                        addresses = addrs
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating network interfaces: ${e.message}")
        }

        // Check WifiManager DHCP Gateway on client side
        var dhcpGateway: String? = null
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val gatewayInt = wm?.dhcpInfo?.gateway ?: 0
            if (gatewayInt != 0) {
                @Suppress("DEPRECATION")
                dhcpGateway = Formatter.formatIpAddress(gatewayInt)
            }
        } catch (_: Exception) {}

        _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
            localInterfaces = interfaces,
            detectedLocalIp = bestLocalIp ?: bestHotspotIp,
            detectedHotspotIp = bestHotspotIp ?: bestLocalIp,
            dhcpGatewayIp = dhcpGateway
        )

        return interfaces
    }

    override suspend fun startServer(port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        disconnect()
        _status.value = TransportStatus.STARTING
        logEvent("SERVER_START", "Starting TCP Server on port $port")

        if (!ConnectionDiagnostics.validatePort(port)) {
            val err = ConnectionDiagnostics.classifyException(
                IllegalArgumentException("Invalid port number: $port (valid range is 1-65535)"),
                targetPort = port
            )
            _connectionDiagnostics.value = _connectionDiagnostics.value.copy(lastError = err)
            _status.value = TransportStatus.ERROR
            return@withContext Result.failure(IllegalArgumentException(err.exceptionMessage))
        }

        try {
            refreshNetworkInterfaces()

            val server = ServerSocket()
            server.reuseAddress = true
            val bindAddr = InetSocketAddress("0.0.0.0", port)
            server.bind(bindAddr, 50)
            serverSocket = server

            logEvent("SERVER_BIND", "ServerSocket bound to ${server.localSocketAddress} on port ${server.localPort}")

            _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                role = "SERVER (Phone A)",
                transportName = "Local Wi-Fi TCP",
                serverBindAddress = "0.0.0.0:$port",
                serverLocalSocketAddress = "${server.localSocketAddress}",
                serverLocalPort = server.localPort,
                serverIsBound = server.isBound,
                serverIsClosed = server.isClosed,
                acceptState = "WAITING_FOR_CLIENT",
                incomingConnectionReachedAccept = false,
                lastError = null
            )

            _status.value = TransportStatus.LISTENING
            val detectedIp = _connectionDiagnostics.value.detectedLocalIp ?: "0.0.0.0"
            logEvent("SERVER_LISTENING", "Listening on 0.0.0.0:$port (Reachable at $detectedIp:$port)")

            registerNsdService(port)

            listeningJob = scope.launch {
                try {
                    while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                        logEvent("SERVER_LISTENING", "accept() blocked waiting for incoming peer connection...")
                        val socket = serverSocket!!.accept()
                        
                        val remoteIp = socket.inetAddress.hostAddress
                        val remotePort = socket.port
                        logEvent("PEER_ACCEPTED", "Incoming connection accepted from $remoteIp:$remotePort")

                        _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                            acceptState = "CLIENT_ACCEPTED ($remoteIp:$remotePort)",
                            incomingConnectionReachedAccept = true,
                            lastClientConnectedIp = remoteIp,
                            lastClientConnectedPort = remotePort
                        )

                        handleConnectedSocket(socket)
                    }
                } catch (e: Exception) {
                    if (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                        logEvent("SERVER_ERROR", "ServerSocket accept exception: ${e.javaClass.simpleName}: ${e.message}")
                        val err = ConnectionDiagnostics.classifyException(e, targetPort = port)
                        _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                            acceptState = "ERROR: ${e.message}",
                            lastError = err
                        )
                        _status.value = TransportStatus.ERROR
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logEvent("SERVER_ERROR", "Failed to start server on port $port: ${e.message}")
            val err = ConnectionDiagnostics.classifyException(e, targetPort = port)
            _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                serverIsBound = false,
                serverIsClosed = true,
                acceptState = "FAILED_TO_START",
                lastError = err
            )
            _status.value = TransportStatus.ERROR
            Result.failure(e)
        }
    }

    override suspend fun connectToPeer(targetAddress: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        disconnect()
        val trimmedAddress = targetAddress.trim()

        if (!ConnectionDiagnostics.validateIpAddress(trimmedAddress)) {
            val err = ConnectionDiagnostics.classifyException(
                IllegalArgumentException("Invalid IPv4 address: '$trimmedAddress'. Must be a valid IPv4 address (e.g. 192.168.43.1)."),
                targetIp = trimmedAddress,
                targetPort = port
            )
            _connectionDiagnostics.value = _connectionDiagnostics.value.copy(lastError = err)
            _status.value = TransportStatus.ERROR
            logEvent("CLIENT_CONNECT_FAILURE", "Validation error: ${err.exceptionMessage}")
            return@withContext Result.failure(IllegalArgumentException(err.exceptionMessage))
        }

        if (!ConnectionDiagnostics.validatePort(port)) {
            val err = ConnectionDiagnostics.classifyException(
                IllegalArgumentException("Invalid port number: $port (valid range 1-65535)"),
                targetIp = trimmedAddress,
                targetPort = port
            )
            _connectionDiagnostics.value = _connectionDiagnostics.value.copy(lastError = err)
            _status.value = TransportStatus.ERROR
            logEvent("CLIENT_CONNECT_FAILURE", "Validation error: ${err.exceptionMessage}")
            return@withContext Result.failure(IllegalArgumentException(err.exceptionMessage))
        }

        _status.value = TransportStatus.CONNECTING
        val startTimestamp = System.currentTimeMillis()

        _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
            role = "CLIENT (Phone B)",
            transportName = "Local Wi-Fi TCP",
            targetIp = trimmedAddress,
            targetPort = port,
            connectionStartTimestamp = startTimestamp,
            connectionTimeoutMs = CONNECT_TIMEOUT_MS,
            lastError = null,
            socketIsConnected = false,
            socketIsClosed = true
        )

        logEvent("CLIENT_CONNECT_START", "Initiating TCP connection to $trimmedAddress:$port (Timeout: ${CONNECT_TIMEOUT_MS}ms)")

        val socket = Socket()
        var boundToWifi = false
        var wifiFound = false

        // BIND SOCKET TO LOCAL WI-FI NETWORK:
        // On Android, if Phone B has mobile data enabled while connected to an offline Wi-Fi hotspot,
        // Android default routing sends traffic to cellular. network.bindSocket(socket) forces traffic through Wi-Fi.
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val networks = cm.allNetworks
                for (net in networks) {
                    val caps = cm.getNetworkCapabilities(net)
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        wifiFound = true
                        try {
                            net.bindSocket(socket)
                            boundToWifi = true
                            logEvent("WIFI_BIND", "Successfully bound client socket to Wi-Fi network ($net)")
                        } catch (e: Exception) {
                            logEvent("WIFI_BIND_WARN", "Could not bind socket to Wi-Fi network: ${e.message}")
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            logEvent("WIFI_BIND_WARN", "ConnectivityManager query failed: ${e.message}")
        }

        _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
            boundToWifiNetwork = boundToWifi,
            wifiNetworkFound = wifiFound
        )

        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true

            val endpoint = InetSocketAddress(trimmedAddress, port)
            socket.connect(endpoint, CONNECT_TIMEOUT_MS)

            val durationMs = System.currentTimeMillis() - startTimestamp
            logEvent("CLIENT_CONNECT_SUCCESS", "Connected to $trimmedAddress:$port in ${durationMs}ms (Local: ${socket.localSocketAddress})")

            _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                connectionDurationMs = durationMs,
                clientLocalSocketAddress = "${socket.localSocketAddress}",
                clientRemoteSocketAddress = "${socket.remoteSocketAddress}",
                socketIsConnected = socket.isConnected,
                socketIsClosed = socket.isClosed,
                lastError = null
            )

            handleConnectedSocket(socket)
            Result.success(Unit)
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTimestamp
            val errorDetail = ConnectionDiagnostics.classifyException(e, trimmedAddress, port, CONNECT_TIMEOUT_MS)
            
            logEvent("CLIENT_CONNECT_FAILURE", "Failed after ${durationMs}ms: [${errorDetail.errorType}] ${errorDetail.exceptionClass}: ${errorDetail.exceptionMessage} (Cause: ${errorDetail.rootCause ?: "None"})")
            
            try {
                socket.close()
            } catch (_: Exception) {}

            _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                connectionDurationMs = durationMs,
                socketIsConnected = false,
                socketIsClosed = true,
                lastError = errorDetail
            )
            _status.value = TransportStatus.ERROR
            Result.failure(e)
        }
    }

    private fun handleConnectedSocket(socket: Socket) {
        // Clean up previous active socket if any
        try {
            activeSocket?.close()
        } catch (_: Exception) {}

        activeSocket = socket
        dataOutputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        _connectedPeerName.value = "${socket.inetAddress.hostAddress}:${socket.port}"
        _status.value = TransportStatus.CONNECTED

        _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
            socketIsConnected = true,
            socketIsClosed = false,
            clientRemoteSocketAddress = "${socket.remoteSocketAddress}",
            clientLocalSocketAddress = "${socket.localSocketAddress}"
        )

        readingJob?.cancel()
        readingJob = scope.launch {
            val dis = DataInputStream(socket.getInputStream())
            logEvent("RX_START", "Listening for incoming packets from ${socket.remoteSocketAddress}")

            try {
                val headerBuffer = ByteArray(VaaniPacket.PRE_PAYLOAD_HEADER_SIZE)
                while (isActive && !socket.isClosed && socket.isConnected) {
                    // Read 24-byte pre-payload header
                    dis.readFully(headerBuffer)

                    // Verify magic bytes 'VS' (0x56 0x53)
                    if (headerBuffer[0] != VaaniPacket.MAGIC_BYTE_1 || headerBuffer[1] != VaaniPacket.MAGIC_BYTE_2) {
                        logEvent("RX_SYNC_WARN", "Invalid magic bytes (0x${String.format("%02X", headerBuffer[0])} 0x${String.format("%02X", headerBuffer[1])}). Resyncing...")
                        continue
                    }

                    // Extract payload length from header bytes 22..23
                    val payloadLength = ((headerBuffer[22].toInt() and 0xFF) shl 8) or (headerBuffer[23].toInt() and 0xFF)
                    val fullPacketBytes = ByteArray(VaaniPacket.PRE_PAYLOAD_HEADER_SIZE + payloadLength + VaaniPacket.CRC_SIZE)
                    System.arraycopy(headerBuffer, 0, fullPacketBytes, 0, VaaniPacket.PRE_PAYLOAD_HEADER_SIZE)

                    // Read payload + 4-byte CRC32 trailer
                    dis.readFully(fullPacketBytes, VaaniPacket.PRE_PAYLOAD_HEADER_SIZE, payloadLength + VaaniPacket.CRC_SIZE)

                    // Deserialize and verify CRC32
                    val packet = VaaniPacket.deserialize(fullPacketBytes)
                    logEvent("CRC_OK", "RX Packet: seq=${packet.sequenceNumber}, lang=${packet.languageCode}, size=${fullPacketBytes.size}B, text='${packet.textPayload}'")

                    // Handle test packet if received
                    if (packet.textPayload == "VAANISETU_TEST") {
                        logEvent("RX", "Received VAANISETU_TEST. Replying with VAANISETU_TEST_ACK...")
                        _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                            testStatus = TestPacketStatus.RX_OK,
                            testMessageDetails = "Received VAANISETU_TEST from peer (CRC OK). Reply sent."
                        )
                        val ackPacket = VaaniPacket(
                            priority = MessagePriority.NORMAL,
                            sequenceNumber = packet.sequenceNumber,
                            languageCode = "en",
                            textPayload = "VAANISETU_TEST_ACK"
                        )
                        sendPacket(ackPacket)
                    } else if (packet.textPayload == "VAANISETU_TEST_ACK") {
                        logEvent("RX", "Received VAANISETU_TEST_ACK! Connection test fully verified.")
                        _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                            testStatus = TestPacketStatus.CRC_OK,
                            testMessageDetails = "Test Succeeded! TX OK, RX OK, CRC OK."
                        )
                    }

                    _receivedPackets.emit(packet)
                }
            } catch (e: Exception) {
                if (isActive && !socket.isClosed) {
                    logEvent("PEER_DISCONNECTED", "Connection dropped with ${socket.remoteSocketAddress}: ${e.message}")
                    _status.value = TransportStatus.DISCONNECTED
                    _connectedPeerName.value = null
                    _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                        socketIsConnected = false,
                        socketIsClosed = true
                    )
                }
            }
        }
    }

    override suspend fun sendPacket(packet: VaaniPacket): Result<Unit> = withContext(Dispatchers.IO) {
        val dos = dataOutputStream ?: return@withContext Result.failure(IllegalStateException("Not connected to peer"))
        try {
            val bytes = VaaniPacket.serialize(packet)
            dos.write(bytes)
            dos.flush()
            logEvent("TX", "Sent Packet: seq=${packet.sequenceNumber}, lang=${packet.languageCode}, bytes=${bytes.size}B, text='${packet.textPayload}'")
            Result.success(Unit)
        } catch (e: Exception) {
            logEvent("TX_ERROR", "Failed to send packet: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun sendTestPacket(): Result<Unit> = withContext(Dispatchers.IO) {
        _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
            testStatus = TestPacketStatus.TX_SENDING,
            testMessageDetails = "Sending VAANISETU_TEST packet..."
        )
        val testPacket = VaaniPacket(
            priority = MessagePriority.NORMAL,
            sequenceNumber = System.currentTimeMillis() % 100000,
            languageCode = "en",
            textPayload = "VAANISETU_TEST"
        )
        val result = sendPacket(testPacket)
        if (result.isSuccess) {
            _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                testStatus = TestPacketStatus.TX_OK,
                testMessageDetails = "VAANISETU_TEST sent (TX OK). Awaiting peer ACK..."
            )
        } else {
            _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                testStatus = TestPacketStatus.FAILED,
                testMessageDetails = "Failed to send test packet: ${result.exceptionOrNull()?.message}"
            )
        }
        result
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            logEvent("CLIENT_DISCONNECT", "Disconnecting all active sockets and stopping server...")
            _status.value = TransportStatus.DISCONNECTING

            unregisterNsdService()

            listeningJob?.cancel()
            listeningJob = null

            readingJob?.cancel()
            readingJob = null

            try {
                dataOutputStream?.close()
                activeSocket?.close()
                serverSocket?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Exception during disconnect: ${e.message}")
            } finally {
                activeSocket = null
                serverSocket = null
                dataOutputStream = null
                _status.value = TransportStatus.DISCONNECTED
                _connectedPeerName.value = null
                _connectionDiagnostics.value = _connectionDiagnostics.value.copy(
                    serverIsBound = false,
                    serverIsClosed = true,
                    socketIsConnected = false,
                    socketIsClosed = true,
                    acceptState = "CLOSED"
                )
            }
        }
    }

    private fun registerNsdService(port: Int) {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = SERVICE_NAME
                serviceType = SERVICE_TYPE
                setPort(port)
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    logEvent("NSD", "NSD Service registered: ${serviceInfo.serviceName} on port $port")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    logEvent("NSD_WARN", "NSD Registration failed: errorCode=$errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    logEvent("NSD", "NSD Service unregistered")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    logEvent("NSD_WARN", "NSD Unregistration failed: errorCode=$errorCode")
                }
            })
        } catch (e: Exception) {
            logEvent("NSD_WARN", "NSD Exception: ${e.message}")
        }
    }

    private fun unregisterNsdService() {
        try {
            // NsdManager cleanup
        } catch (_: Exception) {}
    }
}
