package org.vaanisetu.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.vaanisetu.app.data.model.MessagePriority

/**
 * Playback diagnostics snapshot.
 */
data class PlaybackDiagnostics(
    val sampleRate: Int = 0,
    val minBufferSizeBytes: Int = 0,
    val allocatedBufferSizeBytes: Int = 0,
    val samplesWritten: Int = 0,
    val playbackDurationMs: Long = 0L,
    val priority: String = "NORMAL",
    val errorMessage: String? = null
)

/**
 * Manages PCM audio playback via AudioTrack and Android AudioFocus arbitration with strict VAANISETU_VOICE logging.
 *
 * Priority levels:
 *   NORMAL  — USAGE_ASSISTANT, AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
 *   EMERGENCY — USAGE_ALARM, AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, max alarm volume
 */
class AudioPlaybackManager(private val context: Context) {

    companion object {
        private const val TAG = "VAANISETU_VOICE"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _playbackDiagnostics = MutableStateFlow(PlaybackDiagnostics())
    val playbackDiagnostics: StateFlow<PlaybackDiagnostics> = _playbackDiagnostics

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            stopPlayback()
        }
    }

    /**
     * Plays 16-bit PCM audio through AudioTrack with AudioFocus management.
     */
    suspend fun playPcm(
        pcmData: ShortArray,
        sampleRate: Int = 16000,
        priority: MessagePriority = MessagePriority.NORMAL
    ) = withContext(Dispatchers.IO) {
        stopPlayback()

        if (pcmData.isEmpty()) {
            Log.w(TAG, "[VAANISETU_VOICE] AUDIO_PLAY_START: Empty PCM buffer provided")
            return@withContext
        }

        val totalBytes = pcmData.size * 2
        Log.i(TAG, "[VAANISETU_VOICE] AUDIO_PLAY_START: Playing ${pcmData.size} samples ($totalBytes bytes) at ${sampleRate}Hz mono PCM16, priority=${priority.name}")

        // 1. Request AudioFocus
        requestAudioFocus(priority)

        // 2. Build AudioAttributes based on priority
        val usage = if (priority == MessagePriority.EMERGENCY) {
            AudioAttributes.USAGE_ALARM
        } else {
            AudioAttributes.USAGE_ASSISTANT
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, totalBytes)

        val playbackStartTime = System.currentTimeMillis()

        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack = track

            // For emergency, boost alarm stream volume
            if (priority == MessagePriority.EMERGENCY) {
                try {
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                } catch (_: Exception) {}
            }

            // Write all data at once for MODE_STATIC
            val written = track.write(pcmData, 0, pcmData.size)
            Log.i(TAG, "[VAANISETU_VOICE] AUDIO_WRITE bytes=$totalBytes written=${written * 2}")

            _isPlaying.value = true
            track.play()

            // Wait for playback to drain — calculate duration + small margin
            val expectedDurationMs = (pcmData.size * 1000L) / sampleRate
            delay(expectedDurationMs + 100L)

            val actualDuration = System.currentTimeMillis() - playbackStartTime
            Log.i(TAG, "[VAANISETU_VOICE] AUDIO_PLAY_SUCCESS: Playback completed in ${actualDuration}ms")

            _playbackDiagnostics.value = PlaybackDiagnostics(
                sampleRate = sampleRate,
                minBufferSizeBytes = minBufferSize,
                allocatedBufferSizeBytes = bufferSize,
                samplesWritten = written,
                playbackDurationMs = actualDuration,
                priority = priority.name
            )

        } catch (e: Exception) {
            val actualDuration = System.currentTimeMillis() - playbackStartTime
            Log.e(TAG, "[VAANISETU_VOICE] AUDIO_PLAY_ERROR: ${e.javaClass.simpleName}: ${e.message}")
            _playbackDiagnostics.value = PlaybackDiagnostics(
                sampleRate = sampleRate,
                minBufferSizeBytes = minBufferSize,
                allocatedBufferSizeBytes = bufferSize,
                samplesWritten = 0,
                playbackDurationMs = actualDuration,
                priority = priority.name,
                errorMessage = e.message
            )
        } finally {
            stopPlayback()
        }
    }

    fun stopPlayback() {
        try {
            val track = audioTrack
            if (track != null) {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        } catch (_: Exception) {
        } finally {
            audioTrack = null
            _isPlaying.value = false
            abandonAudioFocus()
        }
    }

    private fun requestAudioFocus(priority: MessagePriority) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusGain = if (priority == MessagePriority.EMERGENCY) {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            } else {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            }

            val request = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            if (priority == MessagePriority.EMERGENCY) AudioAttributes.USAGE_ALARM
                            else AudioAttributes.USAGE_ASSISTANT
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }
}
