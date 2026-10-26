package org.vaanisetu.app

import org.junit.Assert.*
import org.junit.Test
import org.vaanisetu.app.data.model.LocationFreshness
import org.vaanisetu.app.data.model.MessagePriority
import org.vaanisetu.app.data.model.SourceLocationInfo
import org.vaanisetu.app.data.model.VaaniPacket

class VaaniPacketLocationTest {

    @Test
    fun testV1BackwardCompatibility() {
        val v1Packet = VaaniPacket(
            protocolVersion = VaaniPacket.VERSION_1,
            priority = MessagePriority.NORMAL,
            sequenceNumber = 101L,
            timestampT3 = 1718000000000L,
            senderIdHash = 0x44332211,
            languageCode = "hi",
            textPayload = "नमस्ते, यह पुराना संस्करण १ पैकेट है।"
        )

        val bytes = VaaniPacket.serialize(v1Packet)
        assertNotNull(bytes)

        val deserialized = VaaniPacket.deserialize(bytes)
        assertEquals(VaaniPacket.VERSION_1, deserialized.protocolVersion)
        assertEquals(v1Packet.textPayload, deserialized.textPayload)
        assertEquals(v1Packet.languageCode, deserialized.languageCode)
        assertNull(deserialized.location)
    }

    @Test
    fun testV2WithoutLocation() {
        val v2Packet = VaaniPacket(
            protocolVersion = VaaniPacket.VERSION_2,
            priority = MessagePriority.NORMAL,
            sequenceNumber = 102L,
            timestampT3 = 1718000000000L,
            senderIdHash = 0x12345678,
            languageCode = "en",
            textPayload = "Hello from phone A without location.",
            location = null
        )

        val bytes = VaaniPacket.serialize(v2Packet)
        val deserialized = VaaniPacket.deserialize(bytes)

        assertEquals(VaaniPacket.VERSION_2, deserialized.protocolVersion)
        assertEquals(v2Packet.textPayload, deserialized.textPayload)
        assertNull(deserialized.location)
    }

    @Test
    fun testV2WithFreshGnssLocation() {
        val now = System.currentTimeMillis()
        val locationInfo = SourceLocationInfo(
            hasLocation = true,
            latitude = 12.9715987,
            longitude = 77.5945627,
            accuracyMeters = 3.5f,
            locationTimestamp = now,
            locationFreshness = LocationFreshness.FRESH,
            locationSource = "GNSS",
            altitudeMeters = 920.5,
            address = null
        )

        val v2Packet = VaaniPacket(
            protocolVersion = VaaniPacket.VERSION_2,
            priority = MessagePriority.EMERGENCY,
            sequenceNumber = 103L,
            timestampT3 = now,
            senderIdHash = 0x55667788,
            languageCode = "ta",
            textPayload = "அவசர உதவி தேவை!",
            location = locationInfo
        )

        val bytes = VaaniPacket.serialize(v2Packet)
        val deserialized = VaaniPacket.deserialize(bytes)

        assertEquals(VaaniPacket.VERSION_2, deserialized.protocolVersion)
        assertEquals(MessagePriority.EMERGENCY, deserialized.priority)
        assertEquals(v2Packet.textPayload, deserialized.textPayload)
        assertEquals("ta", deserialized.languageCode)

        assertNotNull(deserialized.location)
        val loc = deserialized.location!!
        assertTrue(loc.hasLocation)
        assertTrue(loc.isValid())
        assertEquals(12.9715987, loc.latitude, 0.000001)
        assertEquals(77.5945627, loc.longitude, 0.000001)
        assertEquals(3.5f, loc.accuracyMeters, 0.01f)
        assertEquals(now, loc.locationTimestamp)
        assertEquals(LocationFreshness.FRESH, loc.locationFreshness)
        assertEquals("GNSS", loc.locationSource)
        assertEquals(920.5, loc.altitudeMeters, 0.1)
    }

    @Test
    fun testV2WithLastKnownLocationAndAddress() {
        val fixTime = System.currentTimeMillis() - 120_000L // 2 minutes ago
        val locationInfo = SourceLocationInfo(
            hasLocation = true,
            latitude = 28.613939,
            longitude = 77.209021,
            accuracyMeters = 15.0f,
            locationTimestamp = fixTime,
            locationFreshness = LocationFreshness.LAST_KNOWN,
            locationSource = "GPS",
            altitudeMeters = 216.0,
            address = "New Delhi, India"
        )

        val v2Packet = VaaniPacket(
            protocolVersion = VaaniPacket.VERSION_2,
            priority = MessagePriority.NORMAL,
            sequenceNumber = 104L,
            languageCode = "hi",
            textPayload = "स्थिति की जाँच करें।",
            location = locationInfo
        )

        val bytes = VaaniPacket.serialize(v2Packet)
        val deserialized = VaaniPacket.deserialize(bytes)

        assertNotNull(deserialized.location)
        val loc = deserialized.location!!
        assertEquals(LocationFreshness.LAST_KNOWN, loc.locationFreshness)
        assertEquals("GPS", loc.locationSource)
        assertEquals("New Delhi, India", loc.address)
        assertTrue(loc.getAgeSeconds(System.currentTimeMillis()) >= 119)
    }

    @Test
    fun testLocationValidationAndFormatting() {
        val validLoc = SourceLocationInfo(
            hasLocation = true,
            latitude = -33.8688,
            longitude = 151.2093,
            accuracyMeters = 5.0f
        )
        assertTrue(validLoc.isValid())
        val formatted = validLoc.formatCoordinates()
        assertTrue(formatted.contains("33.868800° S"))
        assertTrue(formatted.contains("151.209300° E"))

        val invalidLat = SourceLocationInfo(
            hasLocation = true,
            latitude = 95.0,
            longitude = 10.0
        )
        assertFalse(invalidLat.isValid())

        val invalidLon = SourceLocationInfo(
            hasLocation = true,
            latitude = 10.0,
            longitude = 190.0
        )
        assertFalse(invalidLon.isValid())

        val uninitialized = SourceLocationInfo(hasLocation = false)
        assertFalse(uninitialized.isValid())
        assertEquals("Location Unavailable", uninitialized.formatCoordinates())
    }

    @Test
    fun testCrcCorruptionOnLocationBytesFails() {
        val locationInfo = SourceLocationInfo(
            hasLocation = true,
            latitude = 19.0760,
            longitude = 72.8777,
            accuracyMeters = 4.0f,
            locationFreshness = LocationFreshness.FRESH,
            locationSource = "GNSS"
        )

        val packet = VaaniPacket(
            protocolVersion = VaaniPacket.VERSION_2,
            textPayload = "Mumbai emergency test",
            location = locationInfo
        )

        val bytes = VaaniPacket.serialize(packet)
        // Corrupt byte in the location payload region
        bytes[bytes.size - 10] = (bytes[bytes.size - 10].toInt() xor 0xFF).toByte()

        try {
            VaaniPacket.deserialize(bytes)
            fail("Expected IllegalArgumentException on corrupted CRC32")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("CRC32 mismatch") == true)
        }
    }
}
