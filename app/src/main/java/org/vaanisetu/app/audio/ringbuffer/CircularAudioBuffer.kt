package org.vaanisetu.app.audio.ringbuffer

/**
 * Thread-safe Circular Audio Buffer for 16-bit 16kHz PCM audio samples.
 * Pre-allocates memory to avoid garbage collection pressure during live audio streaming.
 */
class CircularAudioBuffer(val capacityInShorts: Int = 16000 * 10) { // 10 seconds capacity
    private val buffer = ShortArray(capacityInShorts)
    private var writeHead = 0
    private var readHead = 0
    private var availableSamples = 0
    private val lock = Any()

    fun write(data: ShortArray, length: Int = data.size): Int = synchronized(lock) {
        val samplesToWrite = Math.min(length, capacityInShorts - availableSamples)
        for (i in 0 until samplesToWrite) {
            buffer[writeHead] = data[i]
            writeHead = (writeHead + 1) % capacityInShorts
        }
        availableSamples += samplesToWrite
        return samplesToWrite
    }

    fun read(destination: ShortArray, length: Int = destination.size): Int = synchronized(lock) {
        val samplesToRead = Math.min(length, availableSamples)
        for (i in 0 until samplesToRead) {
            destination[i] = buffer[readHead]
            readHead = (readHead + 1) % capacityInShorts
        }
        availableSamples -= samplesToRead
        return samplesToRead
    }

    fun readAllAvailable(): ShortArray = synchronized(lock) {
        val result = ShortArray(availableSamples)
        for (i in 0 until availableSamples) {
            result[i] = buffer[readHead]
            readHead = (readHead + 1) % capacityInShorts
        }
        availableSamples = 0
        return result
    }

    fun clear() = synchronized(lock) {
        writeHead = 0
        readHead = 0
        availableSamples = 0
    }

    val size: Int get() = synchronized(lock) { availableSamples }
    val isEmpty: Boolean get() = synchronized(lock) { availableSamples == 0 }
}
