package org.vaanisetu.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.vaanisetu.app.telemetry.BenchmarkEngine

class WerCalculationTest {

    private class MockContext : android.content.ContextWrapper(null)

    @Test
    fun testWerIdenticalSentences() {
        val engine = BenchmarkEngine(MockContext())
        val ref = "this is a test sentence"
        val hyp = "this is a test sentence"

        val wer = engine.calculateWer(ref, hyp)
        assertEquals(0.0, wer, 0.001)
    }

    @Test
    fun testWerWithOneSubstitution() {
        val engine = BenchmarkEngine(MockContext())
        val ref = "voice to text conversion"
        val hyp = "voice to speech conversion" // 1 substitution out of 4 words

        val wer = engine.calculateWer(ref, hyp)
        assertEquals(25.0, wer, 0.001)
    }

    @Test
    fun testWerWithInsertionAndDeletion() {
        val engine = BenchmarkEngine(MockContext())
        val ref = "emergency message broadcast"
        val hyp = "urgent message" // 1 substitution (emergency->urgent) + 1 deletion (broadcast) = 2/3 = 66.67%

        val wer = engine.calculateWer(ref, hyp)
        assertEquals(66.666, wer, 0.01)
    }
}
