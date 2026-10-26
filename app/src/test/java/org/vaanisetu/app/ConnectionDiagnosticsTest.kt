package org.vaanisetu.app

import org.junit.Assert.*
import org.junit.Test
import org.vaanisetu.app.data.model.*
import java.net.*

/**
 * Unit tests for ConnectionDiagnostics, IP/Port validation, error classification,
 * and test packet framing/CRC integrity.
 */
class ConnectionDiagnosticsTest {

    @Test
    fun `validate valid IPv4 addresses`() {
        assertTrue(ConnectionDiagnostics.validateIpAddress("192.168.43.1"))
        assertTrue(ConnectionDiagnostics.validateIpAddress("192.168.1.100"))
        assertTrue(ConnectionDiagnostics.validateIpAddress("10.0.0.1"))
        assertTrue(ConnectionDiagnostics.validateIpAddress("172.16.0.5"))
    }

    @Test
    fun `invalidate improper IP addresses`() {
        assertFalse(ConnectionDiagnostics.validateIpAddress(""))
        assertFalse(ConnectionDiagnostics.validateIpAddress("   "))
        assertFalse(ConnectionDiagnostics.validateIpAddress("localhost"))
        assertFalse(ConnectionDiagnostics.validateIpAddress("127.0.0.1")) // Disallowed for 2-phone D2D
        assertFalse(ConnectionDiagnostics.validateIpAddress("999.999.999.999"))
        assertFalse(ConnectionDiagnostics.validateIpAddress("192.168.1"))
        assertFalse(ConnectionDiagnostics.validateIpAddress("192.168.1.abc"))
    }

    @Test
    fun `validate ports`() {
        assertTrue(ConnectionDiagnostics.validatePort(8899))
        assertTrue(ConnectionDiagnostics.validatePort(80))
        assertTrue(ConnectionDiagnostics.validatePort(1))
        assertTrue(ConnectionDiagnostics.validatePort(65535))

        assertFalse(ConnectionDiagnostics.validatePort(0))
        assertFalse(ConnectionDiagnostics.validatePort(-1))
        assertFalse(ConnectionDiagnostics.validatePort(65536))
        assertFalse(ConnectionDiagnostics.validatePort(100000))
    }

    @Test
    fun `classify connection refused exception`() {
        val ex = ConnectException("Connection refused (ECONNREFUSED)")
        val detail = ConnectionDiagnostics.classifyException(ex, "192.168.43.1", 8899, 5000)

        assertEquals(SocketErrorType.CONNECTION_REFUSED, detail.errorType)
        assertEquals("Connection Refused by Server", detail.title)
        assertTrue(detail.diagnosticHint.contains("rejected the TCP handshake"))
        assertEquals("192.168.43.1", detail.destinationIp)
        assertEquals(8899, detail.destinationPort)
    }

    @Test
    fun `classify socket timeout exception`() {
        val ex = SocketTimeoutException("connect timed out")
        val detail = ConnectionDiagnostics.classifyException(ex, "192.168.43.1", 8899, 5000)

        assertEquals(SocketErrorType.CONNECTION_TIMED_OUT, detail.errorType)
        assertEquals("Connection Timed Out", detail.title)
        assertTrue(detail.diagnosticHint.contains("dropped by Android Hotspot Client/AP Isolation"))
    }

    @Test
    fun `classify no route to host exception`() {
        val ex = NoRouteToHostException("No route to host")
        val detail = ConnectionDiagnostics.classifyException(ex, "192.168.43.1", 8899, 5000)

        assertEquals(SocketErrorType.NO_ROUTE_TO_HOST, detail.errorType)
        assertEquals("No Route to Host", detail.title)
        assertTrue(detail.diagnosticHint.contains("Wi-Fi network interface"))
    }

    @Test
    fun `classify bind exception`() {
        val ex = BindException("Address already in use")
        val detail = ConnectionDiagnostics.classifyException(ex, "0.0.0.0", 8899, 5000)

        assertEquals(SocketErrorType.BIND_FAILURE, detail.errorType)
        assertEquals("Port Already In Use", detail.title)
    }

    @Test
    fun `classify permission denied security exception`() {
        val ex = SecurityException("Permission denied")
        val detail = ConnectionDiagnostics.classifyException(ex, "192.168.43.1", 8899, 5000)

        assertEquals(SocketErrorType.PERMISSION_DENIED, detail.errorType)
        assertEquals("Permission Denied", detail.title)
    }

    @Test
    fun `test packet framing and crc verification`() {
        val testPacket = VaaniPacket(
            priority = MessagePriority.NORMAL,
            sequenceNumber = 42L,
            languageCode = "en",
            textPayload = "VAANISETU_TEST"
        )

        val bytes = VaaniPacket.serialize(testPacket)
        assertTrue(bytes.size >= VaaniPacket.HEADER_SIZE)
        assertEquals(VaaniPacket.MAGIC_BYTE_1, bytes[0])
        assertEquals(VaaniPacket.MAGIC_BYTE_2, bytes[1])

        val deserialized = VaaniPacket.deserialize(bytes)
        assertEquals("VAANISETU_TEST", deserialized.textPayload)
        assertEquals("en", deserialized.languageCode)
        assertEquals(42L, deserialized.sequenceNumber)
        assertEquals(MessagePriority.NORMAL, deserialized.priority)
    }

    @Test
    fun `test packet ack framing and crc verification`() {
        val ackPacket = VaaniPacket(
            priority = MessagePriority.NORMAL,
            sequenceNumber = 42L,
            languageCode = "en",
            textPayload = "VAANISETU_TEST_ACK"
        )

        val bytes = VaaniPacket.serialize(ackPacket)
        val deserialized = VaaniPacket.deserialize(bytes)
        assertEquals("VAANISETU_TEST_ACK", deserialized.textPayload)
    }
}
