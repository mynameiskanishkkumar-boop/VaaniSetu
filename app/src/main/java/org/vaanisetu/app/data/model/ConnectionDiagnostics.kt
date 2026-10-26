package org.vaanisetu.app.data.model

import java.net.*

/**
 * Categorization of socket errors encountered during D2D TCP communication.
 */
enum class SocketErrorType(val label: String) {
    NONE("No Error"),
    CONNECTION_REFUSED("Connection Refused (ECONNREFUSED)"),
    CONNECTION_TIMED_OUT("Connection Timed Out (ETIMEDOUT)"),
    NO_ROUTE_TO_HOST("No Route To Host (EHOSTUNREACH)"),
    NETWORK_UNREACHABLE("Network Unreachable (ENETUNREACH)"),
    PERMISSION_DENIED("Permission Denied (EACCES)"),
    BIND_FAILURE("Bind Failure (EADDRINUSE)"),
    INVALID_IP_OR_PORT("Invalid IP Address or Port"),
    HOTSPOT_CLIENT_ISOLATION_SUSPECTED("Hotspot Client Isolation Suspected"),
    GENERIC_SOCKET_ERROR("Socket Communication Error")
}

/**
 * Status of the lightweight test packet verification loop.
 */
enum class TestPacketStatus {
    IDLE,
    TX_SENDING,
    TX_OK,
    RX_OK,
    CRC_OK,
    FAILED
}

/**
 * Detailed information about a socket error.
 */
data class SocketErrorDetail(
    val errorType: SocketErrorType,
    val title: String,
    val exceptionClass: String,
    val exceptionMessage: String,
    val rootCause: String?,
    val destinationIp: String? = null,
    val destinationPort: Int? = null,
    val timeoutMs: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val diagnosticHint: String = ""
)

/**
 * Snapshot of a network interface.
 */
data class NetworkInterfaceInfo(
    val name: String,
    val displayName: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val addresses: List<String>
)

/**
 * Real-time diagnostic telemetry for D2D TCP connections.
 */
data class ConnectionDiagnostics(
    val role: String = "IDLE", // "SERVER (Phone A)", "CLIENT (Phone B)", "IDLE"
    val transportName: String = "Local Wi-Fi TCP",
    
    // Server-side telemetry (Phone A)
    val localInterfaces: List<NetworkInterfaceInfo> = emptyList(),
    val detectedLocalIp: String? = null,
    val detectedHotspotIp: String? = null,
    val serverBindAddress: String? = null,
    val serverLocalSocketAddress: String? = null,
    val serverLocalPort: Int? = null,
    val serverIsBound: Boolean = false,
    val serverIsClosed: Boolean = true,
    val acceptState: String = "NOT_LISTENING", // "WAITING_FOR_CLIENT", "ACCEPTED", "CLOSED", "ERROR"
    val incomingConnectionReachedAccept: Boolean = false,
    val lastClientConnectedIp: String? = null,
    val lastClientConnectedPort: Int? = null,

    // Client-side telemetry (Phone B)
    val targetIp: String? = null,
    val targetPort: Int? = null,
    val connectionStartTimestamp: Long = 0L,
    val connectionDurationMs: Long = 0L,
    val connectionTimeoutMs: Int = 5000,
    val boundToWifiNetwork: Boolean = false,
    val wifiNetworkFound: Boolean = false,
    val dhcpGatewayIp: String? = null,
    val clientLocalSocketAddress: String? = null,
    val clientRemoteSocketAddress: String? = null,
    val socketIsConnected: Boolean = false,
    val socketIsClosed: Boolean = true,

    // Test Packet verification
    val testStatus: TestPacketStatus = TestPacketStatus.IDLE,
    val testMessageDetails: String = "",

    // Error details & logs
    val lastError: SocketErrorDetail? = null,
    val logHistory: List<String> = emptyList()
) {
    companion object {
        private val IPV4_REGEX = Regex("^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.){3}(25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)$")

        fun validateIpAddress(ip: String): Boolean {
            val trimmed = ip.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.equals("localhost", ignoreCase = true) || trimmed == "127.0.0.1") return false // Disallowed for 2-phone D2D
            return IPV4_REGEX.matches(trimmed)
        }

        fun validatePort(port: Int): Boolean {
            return port in 1..65535
        }

        fun classifyException(
            e: Throwable,
            targetIp: String? = null,
            targetPort: Int? = null,
            timeoutMs: Int? = null
        ): SocketErrorDetail {
            val exceptionClass = e.javaClass.name
            val message = e.message ?: "Unknown socket error"
            val rootCause = e.cause?.let { "${it.javaClass.name}: ${it.message}" }
            val fullStr = "$exceptionClass: $message ${rootCause ?: ""}".lowercase()

            val (errorType, title, hint) = when {
                e is BindException || fullStr.contains("eaddrinuse") || fullStr.contains("address already in use") -> {
                    Triple(
                        SocketErrorType.BIND_FAILURE,
                        "Port Already In Use",
                        "Port ${targetPort ?: 8899} is currently bound or in TIME_WAIT. Reused ServerSocket with reuseAddress=true."
                    )
                }
                e is ConnectException && (fullStr.contains("connection refused") || fullStr.contains("econnrefused")) -> {
                    Triple(
                        SocketErrorType.CONNECTION_REFUSED,
                        "Connection Refused by Server",
                        "Target host ($targetIp) is reachable, but port $targetPort rejected the TCP handshake. Ensure Phone A has tapped 'Start Server' and ServerSocket is actively listening."
                    )
                }
                e is SocketTimeoutException || fullStr.contains("timed out") || fullStr.contains("etimedout") -> {
                    Triple(
                        SocketErrorType.CONNECTION_TIMED_OUT,
                        "Connection Timed Out",
                        "No TCP response from $targetIp:$targetPort within ${timeoutMs ?: 5000}ms. Packets were likely dropped by Android Hotspot Client/AP Isolation, device firewall, or socket routed to cellular network."
                    )
                }
                e is NoRouteToHostException || fullStr.contains("no route to host") || fullStr.contains("ehostunreach") -> {
                    Triple(
                        SocketErrorType.NO_ROUTE_TO_HOST,
                        "No Route to Host",
                        "Device routing table has no route to $targetIp. Verify Phone B is connected to Phone A's Wi-Fi network and that the socket is bound to the Wi-Fi network interface."
                    )
                }
                fullStr.contains("network is unreachable") || fullStr.contains("enetunreach") -> {
                    Triple(
                        SocketErrorType.NETWORK_UNREACHABLE,
                        "Network Unreachable",
                        "No network path to $targetIp. Ensure Phone B's Wi-Fi is connected to the same network and Mobile Data is disabled or Wi-Fi network binding is active."
                    )
                }
                e is SecurityException || fullStr.contains("permission denied") || fullStr.contains("eacces") -> {
                    Triple(
                        SocketErrorType.PERMISSION_DENIED,
                        "Permission Denied",
                        "Android OS rejected network operation. Verify android.permission.INTERNET is declared."
                    )
                }
                e is IllegalArgumentException && (fullStr.contains("port") || fullStr.contains("ip")) -> {
                    Triple(
                        SocketErrorType.INVALID_IP_OR_PORT,
                        "Invalid IP or Port",
                        "The entered IP address ($targetIp) or Port ($targetPort) is invalid. Valid port range is 1-65535."
                    )
                }
                else -> {
                    Triple(
                        SocketErrorType.GENERIC_SOCKET_ERROR,
                        "Socket Error: ${e.javaClass.simpleName}",
                        "Network exception: $message"
                    )
                }
            }

            return SocketErrorDetail(
                errorType = errorType,
                title = title,
                exceptionClass = exceptionClass,
                exceptionMessage = message,
                rootCause = rootCause,
                destinationIp = targetIp,
                destinationPort = targetPort,
                timeoutMs = timeoutMs,
                timestamp = System.currentTimeMillis(),
                diagnosticHint = hint
            )
        }
    }
}
