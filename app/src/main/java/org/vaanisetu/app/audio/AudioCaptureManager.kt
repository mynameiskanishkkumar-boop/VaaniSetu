package org.vaanisetu.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vaanisetu.app.audio.ringbuffer.CircularAudioBuffer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Audio Diagnostics snapshot — reports actual hardware negotiation results.
 */
data class AudioDiagnostics(
    val configuredSampleRate: Int = 0,
    val configuredFrameSize: Int = 0,
    val minBufferSizeBytes: Int = 0,
    val allocatedBufferSizeBytes: Int = 0,
    val audioSource: String = "NONE",
    val audioSourceFallbackUsed: Boolean = false,
    val channelConfig: String = "MONO",
    val encoding: String = "PCM_16BIT",
    val isInitialized: Boolean = false,
    val initErrorMessage: String? = null,
    val totalSamplesCaptured: Long = 0L,
    val lastRms: Double = 0.0,
    val peakAmplitude: Int = 0
)

/**
 * Manages 16kHz 16-bit Mono PCM audio recording using AudioRecord with strict VAANISETU_VOICE telemetry.
 *
 * Audio source selection:
 *   1. Attempts VOICE_RECOGNITION
 *   2. Falls back to MIC
 *   3. Falls back to DEFAULT
 */
class AudioCaptureManager(
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 512 // 32ms per frame @ 16kHz
) {
    companion object {
        private const val TAG = "VAANISETU_VOICE"
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _currentRmsLevel = MutableStateFlow(0f)
    val currentRmsLevel: StateFlow<Float> = _currentRmsLevel

    private val _diagnostics = MutableStateFlow(AudioDiagnostics())
    val diagnostics: StateFlow<AudioDiagnostics> = _diagnostics

    val ringBuffer = CircularAudioBuffer(sampleRate * 15) // 15 seconds capacity

    private var captureStartTime: Long = 0L
    private var totalSamplesReadInSegment: Long = 0L
    private var maxPeakInSegment: Int = 0

    @SuppressLint("MissingPermission")
    fun startCapture(onFrameAvailable: (ShortArray) -> Unit) {
        if (_isRecording.value) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            val errorMsg = "getMinBufferSize failed: device does not support ${sampleRate}Hz mono PCM16"
            Log.e(TAG, "[VAANISETU_VOICE] AudioRecord init ERROR: $errorMsg")
            _diagnostics.value = AudioDiagnostics(
                configuredSampleRate = sampleRate,
                configuredFrameSize = frameSize,
                minBufferSizeBytes = minBufferSize,
                isInitialized = false,
                initErrorMessage = errorMsg
            )
            return
        }

        val bufferSize = maxOf(minBufferSize, frameSize * 4 * 2)

        // Attempt VOICE_RECOGNITION first, then fall back to MIC, then DEFAULT
        val (record, usedSource, fallbackUsed) = tryCreateAudioRecord(bufferSize)

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            val errorMsg = "AudioRecord initialization failed across all audio sources"
            Log.e(TAG, "[VAANISETU_VOICE] AudioRecord init ERROR: $errorMsg")
            _diagnostics.value = AudioDiagnostics(
                configuredSampleRate = sampleRate,
                configuredFrameSize = frameSize,
                minBufferSizeBytes = minBufferSize,
                allocatedBufferSizeBytes = bufferSize,
                audioSource = "FAILED",
                isInitialized = false,
                initErrorMessage = errorMsg
            )
            return
        }

        audioRecord = record
        captureStartTime = System.currentTimeMillis()
        totalSamplesReadInSegment = 0L
        maxPeakInSegment = 0

        Log.i(TAG, "[VAANISETU_VOICE] AudioRecord init SUCCESS: source=$usedSource, sampleRate=${sampleRate}Hz, channel=MONO, format=PCM_16BIT, bufferSize=${bufferSize}B, fallbackUsed=$fallbackUsed")

        _diagnostics.value = AudioDiagnostics(
            configuredSampleRate = sampleRate,
            configuredFrameSize = frameSize,
            minBufferSizeBytes = minBufferSize,
            allocatedBufferSizeBytes = bufferSize,
            audioSource = usedSource,
            audioSourceFallbackUsed = fallbackUsed,
            channelConfig = "MONO",
            encoding = "PCM_16BIT",
            isInitialized = true
        )

        try {
            record.startRecording()
            _isRecording.value = true
            val stateStr = if (record.state == AudioRecord.STATE_INITIALIZED) "STATE_INITIALIZED" else "STATE_UNINITIALIZED"
            val recStateStr = if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) "RECORDSTATE_RECORDING" else "RECORDSTATE_STOPPED"
            Log.i("VAANISETU_STT", "AudioRecord start: state=$stateStr recordingState=$recStateStr sampleRate=${sampleRate}Hz channel=MONO format=PCM_16BIT bufferSize=${bufferSize}B")
            Log.i(TAG, "[VAANISETU_VOICE] CAPTURE_START")

            recordingJob = scope.launch {
                val readBuffer = ShortArray(frameSize)
                while (isActive && _isRecording.value) {
                    val readSamples = audioRecord?.read(readBuffer, 0, frameSize) ?: 0
                    if (readSamples > 0) {
                        totalSamplesReadInSegment += readSamples

                        // Calculate RMS level and peak amplitude for this frame
                        var sum = 0.0
                        var framePeak = 0
                        for (i in 0 until readSamples) {
                            val sample = readBuffer[i].toInt()
                            sum += sample.toDouble() * sample.toDouble()
                            val absSample = abs(sample)
                            if (absSample > framePeak) {
                                framePeak = absSample
                            }
                        }
                        if (framePeak > maxPeakInSegment) {
                            maxPeakInSegment = framePeak
                        }

                        val rms = sqrt(sum / readSamples)
                        val normalizedRms = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                        _currentRmsLevel.value = normalizedRms

                        _diagnostics.value = _diagnostics.value.copy(
                            totalSamplesCaptured = totalSamplesReadInSegment,
                            lastRms = rms,
                            peakAmplitude = maxPeakInSegment
                        )

                        // Write to ring buffer
                        ringBuffer.write(readBuffer, readSamples)

                        // Periodic sample telemetry logging (every ~500ms)
                        if (totalSamplesReadInSegment % (sampleRate / 2) == 0L) {
                            Log.i("VAANISETU_STT", "AudioRecord: readCount=$readSamples totalSamples=$totalSamplesReadInSegment PCM_RMS=${String.format("%.1f", rms)} PCM_PEAK=$framePeak")
                            Log.d(TAG, "[VAANISETU_VOICE] PCM_READ samples=$totalSamplesReadInSegment PCM_RMS=${String.format("%.1f", rms)} PCM_PEAK=$framePeak")
                        }

                        // Callback to VAD / consumer
                        val frameCopy = ShortArray(readSamples)
                        System.arraycopy(readBuffer, 0, frameCopy, 0, readSamples)
                        onFrameAvailable(frameCopy)
                    } else if (readSamples < 0) {
                        Log.e("VAANISETU_STT", "AudioRecord read error code: $readSamples")
                        Log.e(TAG, "[VAANISETU_VOICE] AudioRecord read error code: $readSamples")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[VAANISETU_VOICE] AudioRecord startRecording exception: ${e.message}")
            _diagnostics.value = _diagnostics.value.copy(
                initErrorMessage = "startRecording exception: ${e.message}"
            )
            stopCapture()
        }
    }

    fun stopCapture() {
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null

        val durationMs = if (captureStartTime > 0) System.currentTimeMillis() - captureStartTime else 0L
        Log.i(TAG, "[VAANISETU_VOICE] CAPTURE_STOP durationMs=$durationMs (totalSamples=$totalSamplesReadInSegment, peakAmplitude=$maxPeakInSegment)")

        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "[VAANISETU_VOICE] AudioRecord release notice: ${e.message}")
        } finally {
            audioRecord = null
            _currentRmsLevel.value = 0f
            captureStartTime = 0L
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryCreateAudioRecord(bufferSize: Int): Triple<AudioRecord?, String, Boolean> {
        // 1. Try VOICE_RECOGNITION
        try {
            val vrRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (vrRecord.state == AudioRecord.STATE_INITIALIZED) {
                return Triple(vrRecord, "VOICE_RECOGNITION", false)
            }
            vrRecord.release()
        } catch (_: Exception) {}

        // 2. Fallback to MIC
        try {
            val micRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (micRecord.state == AudioRecord.STATE_INITIALIZED) {
                return Triple(micRecord, "MIC", true)
            }
            micRecord.release()
        } catch (_: Exception) {}

        // 3. Fallback to DEFAULT
        try {
            val defRecord = AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (defRecord.state == AudioRecord.STATE_INITIALIZED) {
                return Triple(defRecord, "DEFAULT", true)
            }
            defRecord.release()
        } catch (_: Exception) {}

        return Triple(null, "FAILED", true)
    }
}
