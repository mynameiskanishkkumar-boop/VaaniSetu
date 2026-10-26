package org.vaanisetu.app.transport

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.vaanisetu.app.data.model.ConnectionDiagnostics
import org.vaanisetu.app.data.model.MessagePriority
import org.vaanisetu.app.data.model.VaaniPacket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Embedded Device Transport for external hardware bridge nodes
 * (e.g. ESP32 LoRa mesh node, VHF/UHF tactical radio bridge, or USB-UART OTG serial link).
 *
 * Provides standard binary byte-stream framing and communication over a local serial/OTG
 * or TCP-bridge interface without any Internet access.
 */
class EmbeddedBridgeTransport(private val context: Context) : PeerTransport {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _status = MutableStateFlow(TransportStatus.DISCONNECTED)
    override val status: StateFlow<TransportStatus> = _status

    private val _connectedPeerName = MutableStateFlow<String?>(null)
    override val connectedPeerName: StateFlow<String?> = _connectedPeerName

    private val _receivedPackets = MutableSharedFlow<VaaniPacket>(extraBufferCapacity = 64)
    override val receivedPackets: SharedFlow<VaaniPacket> = _receivedPackets

    private val _connectionDiagnostics = MutableStateFlow(ConnectionDiagnostics(role = "BRIDGE", transportName = "Embedded Bridge"))
    override val connectionDiagnostics: StateFlow<ConnectionDiagnostics> = _connectionDiagnostics

    private var activeSocket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var dataOutputStream: DataOutputStream? = null
    private var listeningJob: Job? = null

    var bridgeBaudRate: Int = 115200
    var bridgeDevicePath: String = "/dev/ttyUSB0"

    override suspend fun startServer(port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        disconnect()
        try {
            _status.value = TransportStatus.LISTENING
            _connectedPeerName.value = "Bridge Server :$port"
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("0.0.0.0", port), 50)
            serverSocket = server

            listeningJob = scope.launch {
                try {
                    while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                        val client = serverSocket!!.accept()
                        handleConnectedBridge(client, "Bridge Node (${client.inetAddress.hostAddress})")
                    }
                } catch (e: Exception) {
                    if (isActive) _status.value = TransportStatus.ERROR
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            _status.value = TransportStatus.ERROR
            Result.failure(e)
        }
    }

    override suspend fun connectToPeer(targetAddress: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        disconnect()
        try {
            _status.value = TransportStatus.CONNECTING
            val socket = Socket()
            socket.connect(InetSocketAddress(targetAddress, port), 5000)
            handleConnectedBridge(socket, "Embedded Bridge ($targetAddress:$port)")
            Result.success(Unit)
        } catch (e: Exception) {
            _status.value = TransportStatus.ERROR
            Result.failure(e)
        }
    }

    private fun handleConnectedBridge(socket: Socket, name: String) {
        activeSocket = socket
        dataOutputStream = DataOutputStream(socket.getOutputStream())
        _connectedPeerName.value = name
        _status.value = TransportStatus.CONNECTED

        scope.launch {
            val dis = DataInputStream(socket.getInputStream())
            try {
                val headerBuffer = ByteArray(VaaniPacket.PRE_PAYLOAD_HEADER_SIZE)
                while (isActive && !socket.isClosed) {
                    dis.readFully(headerBuffer)

                    if (headerBuffer[0] != VaaniPacket.MAGIC_BYTE_1 || headerBuffer[1] != VaaniPacket.MAGIC_BYTE_2) {
                        continue
                    }

                    val payloadLength = ((headerBuffer[22].toInt() and 0xFF) shl 8) or (headerBuffer[23].toInt() and 0xFF)
                    val fullPacketBytes = ByteArray(VaaniPacket.PRE_PAYLOAD_HEADER_SIZE + payloadLength + VaaniPacket.CRC_SIZE)
                    System.arraycopy(headerBuffer, 0, fullPacketBytes, 0, VaaniPacket.PRE_PAYLOAD_HEADER_SIZE)

                    dis.readFully(fullPacketBytes, VaaniPacket.PRE_PAYLOAD_HEADER_SIZE, payloadLength + VaaniPacket.CRC_SIZE)

                    val packet = VaaniPacket.deserialize(fullPacketBytes)
                    _receivedPackets.emit(packet)
                }
            } catch (e: Exception) {
                if (isActive) {
                    _status.value = TransportStatus.DISCONNECTED
                    _connectedPeerName.value = null
                }
            }
        }
    }

    override suspend fun sendPacket(packet: VaaniPacket): Result<Unit> = withContext(Dispatchers.IO) {
        val dos = dataOutputStream ?: return@withContext Result.failure(IllegalStateException("Embedded bridge not connected"))
        try {
            val bytes = VaaniPacket.serialize(packet)
            dos.write(bytes)
            dos.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendTestPacket(): Result<Unit> = withContext(Dispatchers.IO) {
        val testPacket = VaaniPacket(
            priority = MessagePriority.NORMAL,
            sequenceNumber = System.currentTimeMillis() % 100000,
            languageCode = "en",
            textPayload = "VAANISETU_TEST"
        )
        sendPacket(testPacket)
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            listeningJob?.cancel()
            listeningJob = null

            try {
                dataOutputStream?.close()
                activeSocket?.close()
                serverSocket?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                dataOutputStream = null
                activeSocket = null
                serverSocket = null
                _status.value = TransportStatus.DISCONNECTED
                _connectedPeerName.value = null
            }
        }
    }
}
