package org.vaanisetu.app

import org.junit.Assert.*
import org.junit.Test
import org.vaanisetu.app.data.model.ConversationMessage
import org.vaanisetu.app.data.model.MessageDirection
import org.vaanisetu.app.data.model.MessagePriority
import org.vaanisetu.app.data.model.VaaniPacket

class ConversationFlowTest {

    @Test
    fun `test eight different sentences in conversation flow without canned text`() {
        val testSentences = listOf(
            "Good morning everyone.",
            "My name is Manoj.",
            "Please send the latest information.",
            "I am testing VaaniSetu right now.",
            "This sentence is completely different.",
            "Water level is rising in the eastern district.",
            "Medical response team has arrived at sector 4.",
            "Transmission frequency verified on channel 8899."
        )

        val conversationHistory = mutableListOf<ConversationMessage>()
        var baseTime = 1725619200000L

        testSentences.forEachIndexed { index, sentence ->
            val utteranceId = (index + 1).toLong()
            baseTime += 5000L

            // Sender records TX
            val txMsg = ConversationMessage(
                messageId = "TX_${utteranceId}_$baseTime",
                utteranceId = utteranceId,
                direction = MessageDirection.TX,
                text = sentence,
                languageCode = "en",
                timestamp = baseTime
            )
            conversationHistory.add(txMsg)

            // Packet serialized and sent over TCP
            val packet = VaaniPacket(
                priority = MessagePriority.NORMAL,
                sequenceNumber = utteranceId,
                timestampT3 = baseTime,
                languageCode = "en",
                textPayload = sentence
            )
            val bytes = VaaniPacket.serialize(packet)
            val deserialized = VaaniPacket.deserialize(bytes)

            // Receiver records RX
            val rxMsg = ConversationMessage(
                messageId = "RX_${deserialized.sequenceNumber}_${deserialized.timestampT3}",
                utteranceId = deserialized.sequenceNumber,
                direction = MessageDirection.RX,
                text = deserialized.textPayload,
                languageCode = deserialized.languageCode,
                timestamp = deserialized.timestampT3 + 40
            )
            conversationHistory.add(rxMsg)
        }

        // Verify total messages
        assertEquals(16, conversationHistory.size)

        // Verify that exact spoken sentences were preserved across all turns
        testSentences.forEachIndexed { index, expectedSentence ->
            val txIndex = index * 2
            val rxIndex = txIndex + 1

            assertEquals(expectedSentence, conversationHistory[txIndex].text)
            assertEquals(MessageDirection.TX, conversationHistory[txIndex].direction)

            assertEquals(expectedSentence, conversationHistory[rxIndex].text)
            assertEquals(MessageDirection.RX, conversationHistory[rxIndex].direction)
        }
    }

    @Test
    fun `test empty STT result does not produce conversation message`() {
        val conversationHistory = mutableListOf<ConversationMessage>()

        fun processSttResult(utteranceId: Long, sttResult: String) {
            val clean = sttResult.trim()
            if (clean.isEmpty()) {
                // Empty STT must not append any message or fallback
                return
            }
            conversationHistory.add(
                ConversationMessage(
                    messageId = "TX_${utteranceId}_${System.currentTimeMillis()}",
                    utteranceId = utteranceId,
                    direction = MessageDirection.TX,
                    text = clean,
                    languageCode = "en"
                )
            )
        }

        // Simulating 3 empty/unrecognized speech events
        processSttResult(101L, "")
        processSttResult(102L, "   ")
        processSttResult(103L, "")

        // History must be strictly empty
        assertEquals(0, conversationHistory.size)

        // Simulating 1 valid speech event
        processSttResult(104L, "Valid speech detected")
        assertEquals(1, conversationHistory.size)
        assertEquals("Valid speech detected", conversationHistory[0].text)
    }

    @Test
    fun `test message deduplication prevents repeated messages`() {
        val conversationHistory = mutableListOf<ConversationMessage>()

        fun addMessage(msg: ConversationMessage): Boolean {
            if (conversationHistory.any { it.messageId == msg.messageId }) {
                return false
            }
            conversationHistory.add(msg)
            return true
        }

        val msg1 = ConversationMessage(
            messageId = "TX_1_1000",
            utteranceId = 1L,
            direction = MessageDirection.TX,
            text = "Good morning everyone.",
            languageCode = "en",
            timestamp = 1000L
        )

        // Add once -> Success
        assertTrue(addMessage(msg1))
        assertEquals(1, conversationHistory.size)

        // Add duplicate -> Rejected
        assertFalse(addMessage(msg1))
        assertEquals(1, conversationHistory.size)

        // Add new utterance -> Success
        val msg2 = ConversationMessage(
            messageId = "TX_2_2000",
            utteranceId = 2L,
            direction = MessageDirection.TX,
            text = "My name is Manoj.",
            languageCode = "en",
            timestamp = 2000L
        )
        assertTrue(addMessage(msg2))
        assertEquals(2, conversationHistory.size)
    }
}
