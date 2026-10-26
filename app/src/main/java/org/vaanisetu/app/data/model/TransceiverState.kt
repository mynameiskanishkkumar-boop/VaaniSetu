package org.vaanisetu.app.data.model

/**
 * Operating state of the VaaniSetu Transceiver pipeline.
 */
enum class TransceiverState {
    READY,          // Standby, waiting for PTT press or VAD voice activation
    LISTENING,      // Microphone actively capturing speech frames
    PROCESSING,     // STT inference running on local device
    SENDING,        // Packaging and transmitting packet over peer transport
    SENT,           // Packet successfully transmitted
    RECEIVING,      // Incoming packet received over peer transport
    SYNTHESIZING,   // Receiver running local TTS engine
    PLAYING,        // Audio playback active on speaker
    ERROR           // Error state with diagnostic message
}

enum class TransceiverMode {
    PUSH_TO_TALK,   // Manual button press-and-hold
    CONTINUOUS      // Hands-free automated VAD voice segmentation
}

enum class TransportType {
    LOCAL_WIFI,     // Direct TCP Socket + NSD
    BLUETOOTH,      // Bluetooth Classic RFCOMM
    EMBEDDED_BRIDGE // UART / LoRa Hardware Bridge
}
