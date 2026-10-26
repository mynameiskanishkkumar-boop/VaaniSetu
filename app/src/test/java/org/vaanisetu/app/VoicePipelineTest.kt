package org.vaanisetu.app

import org.junit.Assert.*
import org.junit.Test
import org.vaanisetu.app.audio.ringbuffer.CircularAudioBuffer
import org.vaanisetu.app.data.model.LanguageRegistry
import org.vaanisetu.app.data.model.MessagePriority
import org.vaanisetu.app.data.model.VaaniPacket

class VoicePipelineTest {

    @Test
    fun `test circular audio buffer write and readAllAvailable`() {
        val buffer = CircularAudioBuffer(16000)
        val input = ShortArray(512) { (it * 10).toShort() }

        buffer.write(input, input.size)
        assertEquals(512, buffer.size)

        val read = buffer.readAllAvailable()
        assertEquals(512, read.size)
        assertEquals(0, buffer.size)
        assertEquals(input[0], read[0])
        assertEquals(input[511], read[511])
    }

    @Test
    fun `test voice packet serialization and deserialization across languages`() {
        LanguageRegistry.languages.forEach { lang ->
            val packet = VaaniPacket(
                priority = MessagePriority.NORMAL,
                sequenceNumber = 101L,
                languageCode = lang.code,
                textPayload = "Test voice payload in ${lang.displayName}"
            )

            val serialized = VaaniPacket.serialize(packet)
            assertTrue(serialized.size > VaaniPacket.HEADER_SIZE)

            val deserialized = VaaniPacket.deserialize(serialized)
            assertEquals(packet.textPayload, deserialized.textPayload)
            assertEquals(packet.languageCode, deserialized.languageCode)
            assertEquals(packet.sequenceNumber, deserialized.sequenceNumber)
            assertEquals(packet.priority, deserialized.priority)
        }
    }

    @Test
    fun `test emergency voice packet priority serialization`() {
        val emergencyPacket = VaaniPacket(
            priority = MessagePriority.EMERGENCY,
            sequenceNumber = 999L,
            languageCode = "hi",
            textPayload = "आपातकालीन सहायता की आवश्यकता है!"
        )

        val serialized = VaaniPacket.serialize(emergencyPacket)
        val deserialized = VaaniPacket.deserialize(serialized)

        assertEquals(MessagePriority.EMERGENCY, deserialized.priority)
        assertEquals("hi", deserialized.languageCode)
        assertEquals("आपातकालीन सहायता की आवश्यकता है!", deserialized.textPayload)
    }
}
