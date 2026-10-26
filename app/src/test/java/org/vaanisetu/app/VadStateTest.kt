package org.vaanisetu.app

import org.junit.Assert.*
import org.junit.Test
import org.vaanisetu.app.ai.vad.SileroVadDetector
import org.vaanisetu.app.ai.vad.VadState

class VadStateTest {

    @Test
    fun testVadTransitionsFromIdleToActiveToSilenceToEnd() {
        val vad = SileroVadDetector(silenceDurationMs = 96, frameDurationMs = 32) // 3 frames silence threshold

        // Synthetic silence frame (near zero amplitude)
        val silenceFrame = ShortArray(512) { 0 }

        // Synthetic active speech frame (high amplitude sine)
        val speechFrame = ShortArray(512) { (Math.sin(it.toDouble()) * 15000).toInt().toShort() }

        // Initially IDLE
        assertEquals(VadState.IDLE, vad.state.value)

        // 1. Process 1st speech frame -> SPEECH_INIT
        vad.processFrame(speechFrame)
        assertEquals(VadState.SPEECH_INIT, vad.state.value)

        // 2. Process consecutive speech frames -> SPEECH_ACTIVE
        vad.processFrame(speechFrame)
        vad.processFrame(speechFrame)
        assertEquals(VadState.SPEECH_ACTIVE, vad.state.value)

        // 3. Process silence frame -> SILENCE
        vad.processFrame(silenceFrame)
        assertEquals(VadState.SILENCE, vad.state.value)

        // 4. Process silence until threshold -> SPEECH_END
        vad.processFrame(silenceFrame)
        val finalState = vad.processFrame(silenceFrame)
        assertEquals(VadState.SPEECH_END, finalState)
    }
}
