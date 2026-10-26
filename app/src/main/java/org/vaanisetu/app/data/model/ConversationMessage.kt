package org.vaanisetu.app.data.model

/**
 * Direction of a conversation message in the transceiver.
 * TX = Outgoing transmission (Spoken by this phone user)
 * RX = Incoming transmission (Received from connected peer phone)
 */
enum class MessageDirection {
    TX, // Transmitted by local user
    RX  // Received from remote peer
}

/**
 * Immutable data model representing a single conversation utterance in the transceiver timeline.
 * Ensures strict chronological ordering, unique ID tracking, and state persistence.
 * Includes optional genuine sender Source Location metadata.
 */
data class ConversationMessage(
    val messageId: String,
    val utteranceId: Long,
    val direction: MessageDirection,
    val text: String,
    val languageCode: String,
    val priority: MessagePriority = MessagePriority.NORMAL,
    val timestamp: Long = System.currentTimeMillis(),
    val location: SourceLocationInfo? = null
)
