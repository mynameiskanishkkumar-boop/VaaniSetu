package org.vaanisetu.app.data.model

/**
 * Real-time Telemetry & Diagnostics data.
 * Adheres to strict evaluation rules: all unmeasured values are null or "NOT MEASURED".
 */
data class TelemetryData(
    val timestampT0: Long = 0L, // Speech start capture
    val timestampT1: Long = 0L, // Speech end / pause detected
    val timestampT2: Long = 0L, // STT complete
    val timestampT3: Long = 0L, // Transmission start
    val timestampT4: Long = 0L, // Receiver packet received
    val timestampT5: Long = 0L, // Receiver TTS synthesis complete
    val timestampT6: Long = 0L, // Audio playback start

    val audioDurationMs: Long = 0L,
    val rawPcmBytes: Long = 0L,
    val transmittedBytes: Long = 0L,

    // Resource utilization
    val memoryPssMb: Double = 0.0,
    val cpuPercent: Double = 0.0,

    // Accuracy test telemetry
    val groundTruthText: String = "",
    val predictedText: String = "",
    val measuredWer: Double? = null // Null until evaluated
) {
    val sttLatencyMs: Long get() = if (timestampT2 > timestampT1 && timestampT1 > 0) timestampT2 - timestampT1 else 0L
    val networkLatencyMs: Long get() = if (timestampT4 > timestampT3 && timestampT3 > 0) timestampT4 - timestampT3 else 0L
    val ttsLatencyMs: Long get() = if (timestampT5 > timestampT4 && timestampT4 > 0) timestampT5 - timestampT4 else 0L
    val endToEndLatencyMs: Long get() = if (timestampT6 > timestampT1 && timestampT1 > 0) timestampT6 - timestampT1 else 0L

    val sttRtf: Double get() = if (audioDurationMs > 0 && sttLatencyMs > 0) sttLatencyMs.toDouble() / audioDurationMs.toDouble() else 0.0
    val ttsRtf: Double get() = if (audioDurationMs > 0 && ttsLatencyMs > 0) ttsLatencyMs.toDouble() / audioDurationMs.toDouble() else 0.0

    val measuredBandwidthReductionPercent: Double get() = if (rawPcmBytes > 0 && transmittedBytes > 0) {
        ((rawPcmBytes - transmittedBytes).toDouble() / rawPcmBytes.toDouble()) * 100.0
    } else 0.0
}
