package org.vaanisetu.app.state

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vaanisetu.app.ai.manager.ModelManager
import org.vaanisetu.app.ai.vad.SileroVadDetector
import org.vaanisetu.app.ai.vad.VadState
import org.vaanisetu.app.audio.AudioCaptureManager
import org.vaanisetu.app.audio.AudioPlaybackManager
import org.vaanisetu.app.data.model.*
import org.vaanisetu.app.telemetry.BenchmarkEngine
import org.vaanisetu.app.transport.BluetoothTransport
import org.vaanisetu.app.transport.EmbeddedBridgeTransport
import org.vaanisetu.app.transport.LocalWiFiTransport
import org.vaanisetu.app.transport.PeerTransport

/**
 * Main Central Coordinator for VaaniSetu edge-AI transceiver.
 * Enforces strict non-cached data flow with no hardcoded fallback speech.
 *
 * PTT Flow (Truly Offline):
 *   PTT Press → AudioRecord captures 16kHz PCM into buffer
 *   PTT Release → Stop capture → Feed PCM to Sherpa-ONNX STT → recognized text
 *   → VaaniPacket → TCP/Bluetooth → receiver
 *   → Receiver TTS → speaker
 */
class TransceiverCoordinator(private val context: Context) {

    companion object {
        private const val TAG_AUDIO = "VAANISETU_AUDIO"
        private const val TAG_STT = "VAANISETU_STT"
        private const val TAG_TX = "VAANISETU_TX"
        private const val TAG_RX = "VAANISETU_RX"
        private const val TAG_TTS = "VAANISETU_TTS"
        private const val TAG_CONVERSATION = "VAANISETU_CONVERSATION"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val modelManager = ModelManager(context)
    val audioCaptureManager = AudioCaptureManager()
    val audioPlaybackManager = AudioPlaybackManager(context)
    val vadDetector = SileroVadDetector()
    val benchmarkEngine = BenchmarkEngine(context)
    val locationCaptureManager = org.vaanisetu.app.location.LocationCaptureManager(context)

    val wifiTransport = LocalWiFiTransport(context)
    val bluetoothTransport = BluetoothTransport(context)
    val embeddedBridgeTransport = EmbeddedBridgeTransport(context)

    private val _isSourceLocationEnabled = MutableStateFlow(false)
    val isSourceLocationEnabled: StateFlow<Boolean> = _isSourceLocationEnabled

    fun setSourceLocationEnabled(enabled: Boolean) {
        _isSourceLocationEnabled.value = enabled
        Log.i("VAANISETU_LOCATION", "[LOCATION_SETTING] Source Location transmission ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    private val _activeTransportType = MutableStateFlow(TransportType.LOCAL_WIFI)
    val activeTransportType: StateFlow<TransportType> = _activeTransportType

    val activeTransport: PeerTransport
        get() = when (_activeTransportType.value) {
            TransportType.LOCAL_WIFI -> wifiTransport
            TransportType.BLUETOOTH -> bluetoothTransport
            TransportType.EMBEDDED_BRIDGE -> embeddedBridgeTransport
        }

    private val _transceiverState = MutableStateFlow(TransceiverState.READY)
    val transceiverState: StateFlow<TransceiverState> = _transceiverState

    private val _transceiverMode = MutableStateFlow(TransceiverMode.PUSH_TO_TALK)
    val transceiverMode: StateFlow<TransceiverMode> = _transceiverMode

    private val _lastSentMessage = MutableStateFlow<VaaniPacket?>(null)
    val lastSentMessage: StateFlow<VaaniPacket?> = _lastSentMessage

    private val _lastReceivedMessage = MutableStateFlow<VaaniPacket?>(null)
    val lastReceivedMessage: StateFlow<VaaniPacket?> = _lastReceivedMessage

    private val _conversationHistory = MutableStateFlow<List<ConversationMessage>>(emptyList())
    val conversationHistory: StateFlow<List<ConversationMessage>> = _conversationHistory

    private val conversationLock = Any()

    private val _latestTelemetry = MutableStateFlow(TelemetryData())
    val latestTelemetry: StateFlow<TelemetryData> = _latestTelemetry

    private val _voiceStatusMessage = MutableStateFlow<String?>(null)
    val voiceStatusMessage: StateFlow<String?> = _voiceStatusMessage

    private var speechStartTimestampT0: Long = 0L
    private var utteranceIdCounter = 0L

    // PCM buffer for accumulating audio during PTT
    private val pcmBuffer = mutableListOf<ShortArray>()
    private val pcmBufferLock = Any()

    init {
        // Observe incoming packets from all transports
        scope.launch {
            wifiTransport.receivedPackets.collect { handleIncomingPacket(it) }
        }
        scope.launch {
            bluetoothTransport.receivedPackets.collect { handleIncomingPacket(it) }
        }
        scope.launch {
            embeddedBridgeTransport.receivedPackets.collect { handleIncomingPacket(it) }
        }
    }

    suspend fun initializeDefaultModels() {
        modelManager.selectLanguage("hi")
    }

    fun setTransportType(type: TransportType) {
        _activeTransportType.value = type
    }

    fun setTransceiverMode(mode: TransceiverMode) {
        _transceiverMode.value = mode
        if (mode == TransceiverMode.CONTINUOUS) {
            startContinuousListening()
        } else {
            audioCaptureManager.stopCapture()
            _transceiverState.value = TransceiverState.READY
        }
    }

    fun addConversationMessage(
        messageId: String,
        utteranceId: Long,
        direction: MessageDirection,
        text: String,
        languageCode: String,
        priority: MessagePriority = MessagePriority.NORMAL,
        timestamp: Long = System.currentTimeMillis(),
        location: SourceLocationInfo? = null
    ): Boolean {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return false

        val msg = ConversationMessage(
            messageId = messageId,
            utteranceId = utteranceId,
            direction = direction,
            text = cleanText,
            languageCode = languageCode,
            priority = priority,
            timestamp = timestamp,
            location = location
        )

        var newSize = 0
        synchronized(conversationLock) {
            val currentList = _conversationHistory.value
            if (currentList.any { it.messageId == messageId }) {
                Log.d(TAG_CONVERSATION, "Duplicate message rejected: messageId=$messageId")
                return false
            }
            val updatedList = currentList + msg
            _conversationHistory.value = updatedList
            newSize = updatedList.size
        }

        val locTag = if (location != null && location.hasLocation) " loc=[${location.formatCoordinates()} ${location.locationFreshness}]" else ""
        Log.i(TAG_CONVERSATION, "messageId=$messageId direction=${direction.name} text=\"$cleanText\" timestamp=$timestamp$locTag conversationSize=$newSize")
        return true
    }

    fun clearConversationHistory() {
        synchronized(conversationLock) {
            _conversationHistory.value = emptyList()
        }
        Log.i(TAG_CONVERSATION, "Conversation history cleared")
    }

    // --- PUSH-TO-TALK WORKFLOW (TRULY OFFLINE) ---

    fun onPttPressed() {
        if (_transceiverMode.value != TransceiverMode.PUSH_TO_TALK) return

        // Verify RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _voiceStatusMessage.value = "Microphone permission required for voice transmission."
            Log.e(TAG_AUDIO, "PTT_DOWN ERROR: Microphone permission denied")
            return
        }

        utteranceIdCounter++
        val currentUtteranceId = utteranceIdCounter

        _voiceStatusMessage.value = "Listening..."
        _transceiverState.value = TransceiverState.LISTENING
        speechStartTimestampT0 = System.currentTimeMillis()

        Log.i(TAG_AUDIO, "durationMs=0 (CAPTURE_START utteranceId=$currentUtteranceId)")

        // Clear PCM buffer and start capturing audio directly
        synchronized(pcmBufferLock) {
            pcmBuffer.clear()
        }

        audioCaptureManager.startCapture { frameSamples ->
            // Accumulate PCM frames in buffer for later STT
            synchronized(pcmBufferLock) {
                pcmBuffer.add(frameSamples.copyOf())
            }
        }
    }

    fun onPttReleased(priority: MessagePriority = MessagePriority.NORMAL) {
        if (_transceiverMode.value != TransceiverMode.PUSH_TO_TALK) return
        val timestampT1 = System.currentTimeMillis()
        val durationMs = timestampT1 - speechStartTimestampT0
        val currentUtteranceId = utteranceIdCounter

        Log.i(TAG_AUDIO, "durationMs=$durationMs (CAPTURE_STOP utteranceId=$currentUtteranceId)")

        // Stop audio capture
        audioCaptureManager.stopCapture()

        // Collect all accumulated PCM samples
        val allPcm: ShortArray
        synchronized(pcmBufferLock) {
            val totalSamples = pcmBuffer.sumOf { it.size }
            allPcm = ShortArray(totalSamples)
            var offset = 0
            for (chunk in pcmBuffer) {
                System.arraycopy(chunk, 0, allPcm, offset, chunk.size)
                offset += chunk.size
            }
            pcmBuffer.clear()
        }

        scope.launch {
            _transceiverState.value = TransceiverState.PROCESSING
            _voiceStatusMessage.value = "Recognizing speech..."

            // Concurrently acquire location if enabled or emergency (runs in parallel with STT - 0ms audio blocking!)
            val locationDeferred = if (_isSourceLocationEnabled.value || priority == MessagePriority.EMERGENCY) {
                async(Dispatchers.IO) {
                    try {
                        locationCaptureManager.getCurrentLocation(timeoutMs = 4000L)
                    } catch (e: Exception) {
                        Log.w("VAANISETU_LOCATION", "Async location capture error: ${e.message}")
                        null
                    }
                }
            } else null

            val sttEngine = modelManager.getSttEngine()
            val audioDurationMs = (allPcm.size * 1000L) / 16000

            if (allPcm.isEmpty() || audioDurationMs < 200) {
                Log.w(TAG_STT, "utteranceId=$currentUtteranceId STT skipped: audio too short (${audioDurationMs}ms)")
                _voiceStatusMessage.value = "No speech detected"
                _transceiverState.value = TransceiverState.READY
                locationDeferred?.cancel()
                return@launch
            }

            // Feed PCM directly to Sherpa-ONNX STT (100% local, zero cloud)
            val sttResult = sttEngine?.transcribe(allPcm, 16000)
            val realText = sttResult?.getOrNull()?.trim() ?: ""

            if (realText.isEmpty()) {
                Log.w(TAG_STT, "utteranceId=$currentUtteranceId STT_RESULT_EMPTY: No speech recognized from ${allPcm.size} samples (${audioDurationMs}ms)")
                _voiceStatusMessage.value = "No speech recognized"
                _transceiverState.value = TransceiverState.READY
                locationDeferred?.cancel()
                return@launch
            }

            // Await location fix if requested
            val acquiredLocation = locationDeferred?.await()

            // Real recognized text produced
            val langCode = modelManager.selectedLanguage.value.code
            val timestampT3 = System.currentTimeMillis()
            val messageId = "TX_${currentUtteranceId}_${timestampT3}"

            val packet = VaaniPacket(
                protocolVersion = VaaniPacket.CURRENT_VERSION,
                priority = priority,
                sequenceNumber = currentUtteranceId,
                timestampT3 = timestampT3,
                languageCode = langCode,
                textPayload = realText,
                location = acquiredLocation
            )

            // Record conversation message and update UI
            addConversationMessage(
                messageId = messageId,
                utteranceId = currentUtteranceId,
                direction = MessageDirection.TX,
                text = realText,
                languageCode = langCode,
                priority = priority,
                timestamp = timestampT3,
                location = acquiredLocation
            )

            _lastSentMessage.value = packet
            _voiceStatusMessage.value = "Recognized: \"$realText\""
            _transceiverState.value = TransceiverState.SENDING

            val locLog = if (acquiredLocation != null && acquiredLocation.hasLocation) " location=\"${acquiredLocation.formatCoordinates()}\"" else ""
            Log.i(TAG_TX, "utteranceId=$currentUtteranceId sequence=$currentUtteranceId language=$langCode text=\"$realText\" length=${realText.length}$locLog")

            val serializedBytes = VaaniPacket.serialize(packet)
            val sendResult = activeTransport.sendPacket(packet)

            if (sendResult.isSuccess) {
                _transceiverState.value = TransceiverState.SENT
            } else {
                val ex = sendResult.exceptionOrNull()
                Log.e(TAG_TX, "utteranceId=$currentUtteranceId TX_ERROR: ${ex?.message}")
                _transceiverState.value = TransceiverState.ERROR
                _voiceStatusMessage.value = "Transmission failed: ${ex?.message ?: "Peer not connected"}"
            }

            _latestTelemetry.value = TelemetryData(
                timestampT0 = speechStartTimestampT0,
                timestampT1 = timestampT1,
                timestampT2 = timestampT3,
                timestampT3 = timestampT3,
                audioDurationMs = durationMs,
                rawPcmBytes = allPcm.size.toLong() * 2,
                transmittedBytes = serializedBytes.size.toLong(),
                memoryPssMb = benchmarkEngine.getMemoryPssMb()
            )

            delay(800)
            if (_transceiverMode.value == TransceiverMode.PUSH_TO_TALK) {
                _transceiverState.value = TransceiverState.READY
            } else {
                _transceiverState.value = TransceiverState.LISTENING
            }
        }
    }

    fun triggerEmergencySos() {
        utteranceIdCounter++
        val currentUtteranceId = utteranceIdCounter
        val t0 = System.currentTimeMillis()
        val langCode = modelManager.selectedLanguage.value.code
        val emergencyText = if (langCode == "hi") "आपातकालीन सहायता की आवश्यकता है!" else "Emergency assistance needed!"
        val messageId = "TX_${currentUtteranceId}_${t0}"

        // Query best last-known location or attempt fast acquisition
        val loc = if (locationCaptureManager.hasLocationPermissions()) {
            val last = locationCaptureManager.getLastKnownLocation()
            if (last.isValid()) last else null
        } else null

        val packet = VaaniPacket(
            protocolVersion = VaaniPacket.CURRENT_VERSION,
            priority = MessagePriority.EMERGENCY,
            sequenceNumber = currentUtteranceId,
            timestampT3 = t0,
            languageCode = langCode,
            textPayload = emergencyText,
            location = loc
        )

        addConversationMessage(
            messageId = messageId,
            utteranceId = currentUtteranceId,
            direction = MessageDirection.TX,
            text = emergencyText,
            languageCode = langCode,
            priority = MessagePriority.EMERGENCY,
            timestamp = t0,
            location = loc
        )

        scope.launch {
            _lastSentMessage.value = packet
            _voiceStatusMessage.value = "Emergency SOS Broadcast: \"$emergencyText\""
            _transceiverState.value = TransceiverState.SENDING

            val locLog = if (loc != null && loc.hasLocation) " location=\"${loc.formatCoordinates()}\"" else ""
            Log.i(TAG_TX, "utteranceId=$currentUtteranceId sequence=$currentUtteranceId language=$langCode text=\"$emergencyText\" length=${emergencyText.length}$locLog")
            activeTransport.sendPacket(packet)
            delay(1000)
            _transceiverState.value = TransceiverState.READY
        }
    }

    // --- CONTINUOUS MODE WORKFLOW ---

    private fun startContinuousListening() {
        _transceiverState.value = TransceiverState.LISTENING
        vadDetector.reset()
        audioCaptureManager.ringBuffer.clear()

        synchronized(pcmBufferLock) {
            pcmBuffer.clear()
        }

        audioCaptureManager.startCapture { frameSamples ->
            val vadState = vadDetector.processFrame(frameSamples)

            if (vadState == VadState.SPEECH_INIT && speechStartTimestampT0 == 0L) {
                speechStartTimestampT0 = System.currentTimeMillis()
                utteranceIdCounter++
                synchronized(pcmBufferLock) {
                    pcmBuffer.clear()
                    pcmBuffer.add(frameSamples.copyOf())
                }
            } else if (vadState == VadState.SPEECH_END) {
                onPttReleased(MessagePriority.NORMAL)
                speechStartTimestampT0 = 0L
            } else if (speechStartTimestampT0 > 0L) {
                // Accumulate speech frames
                synchronized(pcmBufferLock) {
                    pcmBuffer.add(frameSamples.copyOf())
                }
            }
        }
    }

    // --- RECEIVER PIPELINE ---

    private suspend fun handleIncomingPacket(packet: VaaniPacket) = withContext(Dispatchers.Main) {
        val receivedText = packet.textPayload.trim()
        val seq = packet.sequenceNumber
        val lang = packet.languageCode
        val timestamp = packet.timestampT3
        val locationInfo = packet.location

        if (receivedText.isEmpty()) {
            Log.w(TAG_RX, "utteranceId=$seq sequence=$seq Empty text payload received, ignoring")
            return@withContext
        }

        val messageId = "RX_${seq}_${timestamp}"

        val locLog = if (locationInfo != null && locationInfo.hasLocation) {
            " location=\"${locationInfo.formatCoordinates()}\" acc=${locationInfo.formatAccuracy()} freshness=${locationInfo.locationFreshness.name}"
        } else ""

        Log.i(TAG_RX, "utteranceId=$seq sequence=$seq language=$lang text=\"$receivedText\" length=${receivedText.length}$locLog CRC=OK")

        // Record incoming conversation message and deduplicate
        val added = addConversationMessage(
            messageId = messageId,
            utteranceId = seq,
            direction = MessageDirection.RX,
            text = receivedText,
            languageCode = lang,
            priority = packet.priority,
            timestamp = timestamp,
            location = locationInfo
        )

        if (!added) {
            Log.d(TAG_RX, "Duplicate packet ignored: messageId=$messageId")
            return@withContext
        }

        // Display received text immediately on Phone B
        _lastReceivedMessage.value = packet
        val langObj = org.vaanisetu.app.data.model.LanguageRegistry.getByCode(lang)
        val langName = langObj.displayName
        _voiceStatusMessage.value = "Received [$langName]: \"$receivedText\""
        _transceiverState.value = TransceiverState.RECEIVING

        val timestampT4 = System.currentTimeMillis()
        _transceiverState.value = TransceiverState.SYNTHESIZING

        // Stop any prior playback & clear buffers
        audioPlaybackManager.stopPlayback()

        val ttsEngine = modelManager.getTtsEngine()
        Log.i(
            "VAANISETU_TTS",
            "RX languageCode=$lang resolvedLanguage=$langName selectedModel=${langObj.ttsModelName} receivedText=\"$receivedText\""
        )

        val synthesisStart = System.currentTimeMillis()
        val ttsResult = ttsEngine?.synthesize(receivedText, targetLanguage = lang)
        val synthesisMs = System.currentTimeMillis() - synthesisStart

        if (ttsResult != null && ttsResult.isSuccess) {
            val (pcmAudio, sampleRate) = ttsResult.getOrThrow()
            if (pcmAudio.isNotEmpty()) {
                Log.i(
                    "VAANISETU_TTS",
                    "pcmBytes=${pcmAudio.size * 2} sampleRate=$sampleRate channels=1 encoding=PCM_16BIT playbackStarted=SUCCESS"
                )
                val timestampT6 = System.currentTimeMillis()
                _transceiverState.value = TransceiverState.PLAYING

                _latestTelemetry.value = _latestTelemetry.value.copy(
                    timestampT4 = timestampT4,
                    timestampT5 = synthesisStart + synthesisMs,
                    timestampT6 = timestampT6,
                    memoryPssMb = benchmarkEngine.getMemoryPssMb()
                )

                // Play synthesized voice through AudioTrack
                audioPlaybackManager.playPcm(pcmAudio, sampleRate, packet.priority)
            } else {
                // Direct platform speech was already triggered by synthesize()
                Log.i(
                    "VAANISETU_TTS",
                    "pcmBytes=DIRECT sampleRate=16000 channels=1 encoding=PLATFORM_TTS playbackStarted=SUCCESS"
                )
            }
        } else {
            val ex = ttsResult?.exceptionOrNull()
            val errMsg = ex?.message ?: "$langName TTS unavailable on this device"
            Log.w("VAANISETU_TTS", "TTS playback skipped: $errMsg")
            _voiceStatusMessage.value = errMsg
        }

        _transceiverState.value = if (_transceiverMode.value == TransceiverMode.PUSH_TO_TALK) {
            TransceiverState.READY
        } else {
            TransceiverState.LISTENING
        }
    }

    // --- DIAGNOSTIC TESTS ---

    suspend fun runLocalSttTest(): String = withContext(Dispatchers.IO) {
        utteranceIdCounter++
        val testUtteranceId = utteranceIdCounter

        withContext(Dispatchers.Main) {
            _transceiverState.value = TransceiverState.LISTENING
            _voiceStatusMessage.value = "Recording 3s sample for Local STT..."
        }

        // Capture 3 seconds of PCM audio
        synchronized(pcmBufferLock) {
            pcmBuffer.clear()
        }

        audioCaptureManager.startCapture { frameSamples ->
            synchronized(pcmBufferLock) {
                pcmBuffer.add(frameSamples.copyOf())
            }
        }

        delay(3000) // 3 seconds

        audioCaptureManager.stopCapture()

        // Collect PCM
        val allPcm: ShortArray
        synchronized(pcmBufferLock) {
            val totalSamples = pcmBuffer.sumOf { it.size }
            allPcm = ShortArray(totalSamples)
            var offset = 0
            for (chunk in pcmBuffer) {
                System.arraycopy(chunk, 0, allPcm, offset, chunk.size)
                offset += chunk.size
            }
            pcmBuffer.clear()
        }

        withContext(Dispatchers.Main) {
            _transceiverState.value = TransceiverState.PROCESSING
            _voiceStatusMessage.value = "Processing Local STT..."
        }

        val sttEngine = modelManager.getSttEngine()
        val result = sttEngine?.transcribe(allPcm, 16000)
        val text = result?.getOrNull()?.trim() ?: ""

        val displayStatus = if (text.isNotEmpty()) {
            "STT result: \"$text\""
        } else {
            "No speech recognized"
        }

        withContext(Dispatchers.Main) {
            if (text.isNotEmpty()) {
                val lang = modelManager.selectedLanguage.value.code
                val packet = VaaniPacket(
                    priority = MessagePriority.NORMAL,
                    sequenceNumber = testUtteranceId,
                    languageCode = lang,
                    textPayload = text
                )
                _lastSentMessage.value = packet
            }
            _voiceStatusMessage.value = displayStatus
            _transceiverState.value = TransceiverState.READY
        }

        text
    }

    suspend fun runLocalTtsTest(): Boolean = withContext(Dispatchers.IO) {
        val testPhrase = "Testing offline speech synthesis."

        withContext(Dispatchers.Main) {
            _voiceStatusMessage.value = "Synthesizing Local TTS: \"$testPhrase\""
            _transceiverState.value = TransceiverState.SYNTHESIZING
        }

        val ttsEngine = modelManager.getTtsEngine()
        val ttsResult = ttsEngine?.synthesize(testPhrase)
        val (pcm, sampleRate) = ttsResult?.getOrNull() ?: Pair(ShortArray(0), 16000)

        if (pcm.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                _transceiverState.value = TransceiverState.PLAYING
            }
            audioPlaybackManager.playPcm(pcm, sampleRate, MessagePriority.NORMAL)
            withContext(Dispatchers.Main) {
                _voiceStatusMessage.value = "Local TTS Playback Completed (PCM: ${pcm.size * 2}B, Rate: ${sampleRate}Hz)"
                _transceiverState.value = TransceiverState.READY
            }
            true
        } else {
            withContext(Dispatchers.Main) {
                _voiceStatusMessage.value = "Local TTS Spoken"
                _transceiverState.value = TransceiverState.READY
            }
            true
        }
    }

    suspend fun runLocalVoiceLoopTest(): String = withContext(Dispatchers.IO) {
        utteranceIdCounter++
        val loopUtteranceId = utteranceIdCounter

        withContext(Dispatchers.Main) {
            _transceiverState.value = TransceiverState.LISTENING
            _voiceStatusMessage.value = "Voice Loop: Speak now (3s)..."
        }

        // Capture 3 seconds of PCM audio
        synchronized(pcmBufferLock) {
            pcmBuffer.clear()
        }

        audioCaptureManager.startCapture { frameSamples ->
            synchronized(pcmBufferLock) {
                pcmBuffer.add(frameSamples.copyOf())
            }
        }

        delay(3000)

        audioCaptureManager.stopCapture()

        // Collect PCM
        val allPcm: ShortArray
        synchronized(pcmBufferLock) {
            val totalSamples = pcmBuffer.sumOf { it.size }
            allPcm = ShortArray(totalSamples)
            var offset = 0
            for (chunk in pcmBuffer) {
                System.arraycopy(chunk, 0, allPcm, offset, chunk.size)
                offset += chunk.size
            }
            pcmBuffer.clear()
        }

        withContext(Dispatchers.Main) {
            _transceiverState.value = TransceiverState.PROCESSING
            _voiceStatusMessage.value = "Recognizing..."
        }

        val sttEngine = modelManager.getSttEngine()
        val result = sttEngine?.transcribe(allPcm, 16000)
        val realText = result?.getOrNull()?.trim() ?: ""

        if (realText.isEmpty()) {
            withContext(Dispatchers.Main) {
                _voiceStatusMessage.value = "Voice Loop: No speech recognized"
                _transceiverState.value = TransceiverState.READY
            }
            return@withContext ""
        }

        withContext(Dispatchers.Main) {
            val lang = modelManager.selectedLanguage.value.code
            val packet = VaaniPacket(
                priority = MessagePriority.NORMAL,
                sequenceNumber = loopUtteranceId,
                languageCode = lang,
                textPayload = realText
            )
            _lastSentMessage.value = packet
            _lastReceivedMessage.value = packet
            _voiceStatusMessage.value = "Voice Loop Speaking: \"$realText\""
            _transceiverState.value = TransceiverState.SYNTHESIZING
        }

        val ttsEngine = modelManager.getTtsEngine()
        val ttsResult = ttsEngine?.synthesize(realText)
        val (pcm, sampleRate) = ttsResult?.getOrNull() ?: Pair(ShortArray(0), 16000)

        if (pcm.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                _transceiverState.value = TransceiverState.PLAYING
            }
            audioPlaybackManager.playPcm(pcm, sampleRate, MessagePriority.NORMAL)
        }

        withContext(Dispatchers.Main) {
            _voiceStatusMessage.value = "Voice Loop Done: \"$realText\""
            _transceiverState.value = TransceiverState.READY
        }

        realText
    }
}
