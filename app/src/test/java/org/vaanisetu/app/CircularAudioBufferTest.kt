package org.vaanisetu.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.vaanisetu.app.audio.ringbuffer.CircularAudioBuffer

/**
 * Unit tests for CircularAudioBuffer — validates thread-safe ring buffer
 * used for continuous 16kHz PCM audio capture.
 */
class CircularAudioBufferTest {

    private lateinit var buffer: CircularAudioBuffer

    @Before
    fun setUp() {
        buffer = CircularAudioBuffer(capacityInShorts = 100)
    }

    @Test
    fun `empty buffer returns zero samples`() {
        assertEquals(0, buffer.size)
        assertTrue(buffer.isEmpty)

        val dest = ShortArray(10)
        val read = buffer.read(dest)
        assertEquals(0, read)
    }

    @Test
    fun `write and read back exact data`() {
        val input = shortArrayOf(1, 2, 3, 4, 5)
        val written = buffer.write(input)
        assertEquals(5, written)
        assertEquals(5, buffer.size)

        val output = ShortArray(5)
        val read = buffer.read(output)
        assertEquals(5, read)
        assertArrayEquals(input, output)
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `partial read leaves remaining data`() {
        val input = shortArrayOf(10, 20, 30, 40, 50)
        buffer.write(input)

        val partial = ShortArray(3)
        buffer.read(partial)
        assertArrayEquals(shortArrayOf(10, 20, 30), partial)
        assertEquals(2, buffer.size)

        val rest = ShortArray(2)
        buffer.read(rest)
        assertArrayEquals(shortArrayOf(40, 50), rest)
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `readAllAvailable drains buffer completely`() {
        buffer.write(shortArrayOf(100, 200, 300))
        val all = buffer.readAllAvailable()
        assertEquals(3, all.size)
        assertArrayEquals(shortArrayOf(100, 200, 300), all)
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun `write wraps around when capacity exceeded`() {
        // Buffer capacity is 100
        val chunk1 = ShortArray(80) { it.toShort() } // 0..79
        buffer.write(chunk1)

        // Read 60, freeing space
        val discard = ShortArray(60)
        buffer.read(discard)
        assertEquals(20, buffer.size)

        // Write another 70 — should wrap around the internal array
        val chunk2 = ShortArray(70) { (100 + it).toShort() } // 100..169
        val written = buffer.write(chunk2)
        assertEquals(70, written)
        assertEquals(90, buffer.size)

        // Read everything and verify order
        val result = buffer.readAllAvailable()
        assertEquals(90, result.size)

        // First 20 should be leftover from chunk1 (indices 60..79)
        for (i in 0 until 20) {
            assertEquals((60 + i).toShort(), result[i])
        }
        // Next 70 should be chunk2 (100..169)
        for (i in 0 until 70) {
            assertEquals((100 + i).toShort(), result[20 + i])
        }
    }

    @Test
    fun `write drops samples when buffer is full`() {
        // Fill to capacity
        val full = ShortArray(100) { it.toShort() }
        buffer.write(full)
        assertEquals(100, buffer.size)

        // Try writing more — should write 0 since buffer is full
        val overflow = shortArrayOf(999, 998)
        val written = buffer.write(overflow)
        assertEquals(0, written)
        assertEquals(100, buffer.size)
    }

    @Test
    fun `clear resets buffer`() {
        buffer.write(shortArrayOf(1, 2, 3))
        assertEquals(3, buffer.size)

        buffer.clear()
        assertEquals(0, buffer.size)
        assertTrue(buffer.isEmpty)

        // Should be able to write again from the beginning
        buffer.write(shortArrayOf(10, 20))
        assertEquals(2, buffer.size)
        val out = buffer.readAllAvailable()
        assertArrayEquals(shortArrayOf(10, 20), out)
    }

    @Test
    fun `write with explicit length parameter`() {
        val data = shortArrayOf(1, 2, 3, 4, 5)
        val written = buffer.write(data, 3) // only write first 3
        assertEquals(3, written)
        assertEquals(3, buffer.size)

        val out = buffer.readAllAvailable()
        assertArrayEquals(shortArrayOf(1, 2, 3), out)
    }

    @Test
    fun `read with explicit length parameter`() {
        buffer.write(shortArrayOf(10, 20, 30, 40, 50))

        val dest = ShortArray(10) // larger than available
        val read = buffer.read(dest, 2) // request only 2
        assertEquals(2, read)
        assertEquals(10.toShort(), dest[0])
        assertEquals(20.toShort(), dest[1])
        assertEquals(3, buffer.size)
    }

    @Test
    fun `multiple sequential write-read cycles`() {
        // Simulate live audio streaming: write a frame, read a frame, repeat
        for (cycle in 0 until 50) {
            val frame = ShortArray(16) { (cycle * 16 + it).toShort() }
            buffer.write(frame)
            val out = buffer.readAllAvailable()
            assertEquals(16, out.size)
            assertArrayEquals(frame, out)
        }
        assertTrue(buffer.isEmpty)
    }
}
