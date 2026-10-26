package org.vaanisetu.app.transport

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.vaanisetu.app.data.model.ConnectionDiagnostics
import org.vaanisetu.app.data.model.VaaniPacket

/**
 * Explicit connection lifecycle states for VaaniSetu peer transports.
 */
enum class TransportStatus {
    DISCONNECTED,
    STARTING,
    LISTENING,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

/**
 * Common peer-to-peer transport abstraction for VaaniSetu.
 * Supports Local Wi-Fi TCP, Bluetooth Classic RFCOMM, and Embedded Hardware Bridges.
 */
interface PeerTransport {
    val status: StateFlow<TransportStatus>
    val connectedPeerName: StateFlow<String?>
    val receivedPackets: SharedFlow<VaaniPacket>
    val connectionDiagnostics: StateFlow<ConnectionDiagnostics>

    suspend fun startServer(port: Int = 8899): Result<Unit>
    suspend fun connectToPeer(targetAddress: String, port: Int = 8899): Result<Unit>
    suspend fun sendPacket(packet: VaaniPacket): Result<Unit>
    suspend fun sendTestPacket(): Result<Unit>
    suspend fun disconnect()
}
