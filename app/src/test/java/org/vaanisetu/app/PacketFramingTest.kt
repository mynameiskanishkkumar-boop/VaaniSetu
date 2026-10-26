package org.vaanisetu.app

import org.junit.Assert.*
import org.junit.Test
import org.vaanisetu.app.data.model.MessagePriority
import org.vaanisetu.app.data.model.VaaniPacket

class PacketFramingTest {

    @Test
    fun testSerializationAndDeserializationSuccess() {
        val originalPacket = VaaniPacket(
            protocolVersion = 0x01,
            priority = MessagePriority.EMERGENCY,
            sequenceNumber = 42L,
            timestampT3 = 1717000000000L,
            senderIdHash = 0x1A2B3C4D,
            languageCode = "hi",
            textPayload = "यह वाणीसेतु का परीक्षण संदेश है।"
        )

        val bytes = VaaniPacket.serialize(originalPacket)
        assertNotNull(bytes)
        assertTrue(bytes.size >= VaaniPacket.HEADER_SIZE)

        val deserialized = VaaniPacket.deserialize(bytes)
        assertEquals(originalPacket.protocolVersion, deserialized.protocolVersion)
        assertEquals(originalPacket.priority, deserialized.priority)
        assertEquals(originalPacket.sequenceNumber, deserialized.sequenceNumber)
        assertEquals(originalPacket.timestampT3, deserialized.timestampT3)
        assertEquals(originalPacket.senderIdHash, deserialized.senderIdHash)
        assertEquals(originalPacket.languageCode, deserialized.languageCode)
        assertEquals(originalPacket.textPayload, deserialized.textPayload)
    }

    @Test
    fun testCorruptPayloadFailsCrcCheck() {
        val originalPacket = VaaniPacket(
            priority = MessagePriority.NORMAL,
            sequenceNumber = 1L,
            textPayload = "Normal message"
        )

        val bytes = VaaniPacket.serialize(originalPacket)
        // Corrupt one byte in the text payload
        bytes[25] = (bytes[25].toInt() xor 0xFF).toByte()

        try {
            VaaniPacket.deserialize(bytes)
            fail("Expected IllegalArgumentException on CRC mismatch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("CRC32 mismatch") == true)
        }
    }

    @Test
    fun testInvalidMagicBytesFails() {
        val originalPacket = VaaniPacket(textPayload = "Test")
        val bytes = VaaniPacket.serialize(originalPacket)
        // Corrupt magic byte
        bytes[0] = 0x00

        try {
            VaaniPacket.deserialize(bytes)
            fail("Expected IllegalArgumentException on invalid magic bytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Invalid magic bytes") == true)
        }
    }
}
