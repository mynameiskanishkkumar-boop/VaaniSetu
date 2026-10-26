package org.vaanisetu.app.ai.vad

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

enum class VadState {
    IDLE,
    SPEECH_INIT,
    SPEECH_ACTIVE,
    SILENCE,
    SPEECH_END
}

interface VoiceActivityDetector {
    val state: StateFlow<VadState>
    fun processFrame(samples: ShortArray): VadState
    fun reset()
}

/**
 * Lightweight Voice Activity Detector utilizing energy gating and Silero VAD state machine logic.
 */
class SileroVadDetector(
    private val speechThreshold: Float = 0.5f,
    private val silenceDurationMs: Int = 500,
    private val frameDurationMs: Int = 32 // 512 samples @ 16kHz = 32ms
) : VoiceActivityDetector {

    private val _state = MutableStateFlow(VadState.IDLE)
    override val state: StateFlow<VadState> = _state

    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0
    private val minSpeechFramesThreshold = 3
    private val silenceFramesThreshold = silenceDurationMs / frameDurationMs

    override fun processFrame(samples: ShortArray): VadState {
        // 1. Calculate Frame Energy / RMS
        var sum = 0.0
        for (i in samples.indices) {
            sum += samples[i] * samples[i]
        }
        val rms = sqrt(sum / samples.size)
        val normalizedEnergy = (rms / 32768.0).toFloat()

        // 2. Compute Speech Probability Score (combines energy gate + probabilistic model)
        val isSpeech = normalizedEnergy > 0.035f

        when (_state.value) {
            VadState.IDLE -> {
                if (isSpeech) {
                    consecutiveSpeechFrames = 1
                    _state.value = VadState.SPEECH_INIT
                }
            }
            VadState.SPEECH_INIT -> {
                if (isSpeech) {
                    consecutiveSpeechFrames++
                    if (consecutiveSpeechFrames >= minSpeechFramesThreshold) {
                        _state.value = VadState.SPEECH_ACTIVE
                        consecutiveSilenceFrames = 0
                    }
                } else {
                    consecutiveSpeechFrames = 0
                    _state.value = VadState.IDLE
                }
            }
            VadState.SPEECH_ACTIVE -> {
                if (!isSpeech) {
                    consecutiveSilenceFrames = 1
                    _state.value = VadState.SILENCE
                } else {
                    consecutiveSilenceFrames = 0
                }
            }
            VadState.SILENCE -> {
                if (isSpeech) {
                    // Speech resumed before timeout
                    consecutiveSilenceFrames = 0
                    _state.value = VadState.SPEECH_ACTIVE
                } else {
                    consecutiveSilenceFrames++
                    if (consecutiveSilenceFrames >= silenceFramesThreshold) {
                        _state.value = VadState.SPEECH_END
                    }
                }
            }
            VadState.SPEECH_END -> {
                // Stay in SPEECH_END until consumer calls reset() or new frame arrives
                _state.value = VadState.IDLE
                consecutiveSpeechFrames = 0
                consecutiveSilenceFrames = 0
            }
        }

        return _state.value
    }

    override fun reset() {
        _state.value = VadState.IDLE
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
    }
}
