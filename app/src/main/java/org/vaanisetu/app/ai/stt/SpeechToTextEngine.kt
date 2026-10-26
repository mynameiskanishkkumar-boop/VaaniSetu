package org.vaanisetu.app.ai.stt

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.*
import org.vaanisetu.app.data.model.ModelStatus
import java.io.File

/**
 * Speech-to-Text Engine interface for VaaniSetu.
 * All implementations MUST be 100% offline — zero network/cloud calls.
 */
interface SpeechToTextEngine {
    val modelStatus: ModelStatus
    suspend fun initialize(modelDir: File, languageCode: String): Result<Unit>
    suspend fun transcribe(samples: ShortArray, sampleRate: Int = 16000): Result<String>
    fun setLanguage(languageCode: String)
    fun release()
}

/**
 * Truly Offline STT Engine using Sherpa-ONNX Whisper Tiny Multilingual.
 *
 * Runs ONNX inference directly on PCM audio samples.
 * NO Android SpeechRecognizer.
 * NO Google Speech Services.
 * NO cloud API.
 * NO internet dependency.
 *
 * Single Whisper Tiny Multilingual model covers all 10 Indian languages:
 * hi, en, gu, mr, kn, ml, ta, te, or, bn
 */
class SherpaOnnxSttEngine(private val context: Context) : SpeechToTextEngine {

    companion object {
        private const val TAG = "VAANISETU_STT"
        private const val TAG_REAL = "VAANISETU_REAL_STT"

        // Whisper language tokens for each supported language
        private val WHISPER_LANG_MAP = mapOf(
            "hi" to "hi",
            "en" to "en",
            "gu" to "gu",
            "mr" to "mr",
            "kn" to "kn",
            "ml" to "ml",
            "ta" to "ta",
            "te" to "te",
            "or" to "or",
            "bn" to "bn"
        )
    }

    private var _modelStatus: ModelStatus = ModelStatus.UNAVAILABLE
    override val modelStatus: ModelStatus get() = _modelStatus

    private var activeLanguage: String = "en"
    private var isInitialized = false
    private var recognizer: OfflineRecognizer? = null
    private var modelDirectory: File? = null

    override suspend fun initialize(modelDir: File, languageCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.i(TAG, "[STT_INIT_START] Initializing Sherpa-ONNX Whisper Tiny for lang=$languageCode (modelDir=${modelDir.absolutePath})")
        _modelStatus = ModelStatus.LOADING
        activeLanguage = languageCode
        modelDirectory = modelDir

        try {
            val encoderFile = File(modelDir, "tiny-encoder.int8.onnx")
            val decoderFile = File(modelDir, "tiny-decoder.int8.onnx")
            val tokensFile = File(modelDir, "tiny-tokens.txt")

            val encoderExists = encoderFile.exists() && encoderFile.length() > 0
            val decoderExists = decoderFile.exists() && decoderFile.length() > 0
            val tokensExists = tokensFile.exists() && tokensFile.length() > 0

            Log.i(TAG, "[STT_MODEL_PATH] encoder=${encoderFile.absolutePath} decoder=${decoderFile.absolutePath} tokens=${tokensFile.absolutePath}")
            Log.i(TAG, "[STT_MODEL_FILES_EXIST] encoder=$encoderExists (${encoderFile.length()}B) decoder=$decoderExists (${decoderFile.length()}B) tokens=$tokensExists (${tokensFile.length()}B)")

            if (!encoderExists || !decoderExists || !tokensExists) {
                val err = "STT model files missing in ${modelDir.absolutePath}: encoder=$encoderExists, decoder=$decoderExists, tokens=$tokensExists"
                Log.e(TAG, "[STT_INIT_FAILED] $err")
                _modelStatus = ModelStatus.UNAVAILABLE
                return@withContext Result.failure(IllegalStateException(err))
            }

            val whisperLang = WHISPER_LANG_MAP[languageCode.lowercase()] ?: "en"

            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = encoderFile.absolutePath,
                        decoder = decoderFile.absolutePath,
                        language = whisperLang,
                        task = "transcribe",
                        tailPaddings = 1000
                    ),
                    tokens = tokensFile.absolutePath,
                    numThreads = 2,
                    debug = false
                ),
                decodingMethod = "greedy_search"
            )

            // Instantiate native recognizer with absolute file paths
            recognizer = OfflineRecognizer(assetManager = null, config = config)

            _modelStatus = ModelStatus.VERIFIED
            isInitialized = true
            Log.i(TAG, "[STT_INIT_SUCCESS] Sherpa-ONNX Whisper Tiny ready for lang=$languageCode (whisperToken=$whisperLang, cloudApiCalls=0)")
            Result.success(Unit)
        } catch (e: Exception) {
            _modelStatus = ModelStatus.ERROR
            isInitialized = false
            Log.e(TAG, "[STT_ERROR] STT Initialization exception: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun setLanguage(languageCode: String) {
        val oldLang = activeLanguage
        activeLanguage = languageCode

        if (oldLang != languageCode && recognizer != null && modelDirectory != null) {
            val whisperLang = WHISPER_LANG_MAP[languageCode.lowercase()] ?: "en"
            Log.i(TAG, "[VAANISETU_STT] Language switch: $oldLang -> $languageCode (whisperToken=$whisperLang)")

            try {
                val modelDir = modelDirectory!!
                val encoderFile = File(modelDir, "tiny-encoder.int8.onnx")
                val decoderFile = File(modelDir, "tiny-decoder.int8.onnx")
                val tokensFile = File(modelDir, "tiny-tokens.txt")

                recognizer?.release()

                val config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = encoderFile.absolutePath,
                            decoder = decoderFile.absolutePath,
                            language = whisperLang,
                            task = "transcribe",
                            tailPaddings = 1000
                        ),
                        tokens = tokensFile.absolutePath,
                        numThreads = 2,
                        debug = false
                    ),
                    decodingMethod = "greedy_search"
                )

                recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "[STT_INIT_SUCCESS] Language switched to $languageCode successfully")
            } catch (e: Exception) {
                Log.e(TAG, "[STT_ERROR] Language switch failed: ${e.message}", e)
            }
        }
    }

    override suspend fun transcribe(samples: ShortArray, sampleRate: Int): Result<String> = withContext(Dispatchers.IO) {
        val audioDurationMs = if (sampleRate > 0) (samples.size * 1000L) / sampleRate else 0L
        Log.i(TAG, "[STT_INPUT_SAMPLES] count=${samples.size} sampleRate=$sampleRate [STT_INPUT_DURATION_MS] $audioDurationMs ms (lang=$activeLanguage)")

        if (samples.isEmpty()) {
            Log.w(TAG, "[VAANISETU_STT] Empty audio buffer, skipping transcription")
            return@withContext Result.success("")
        }

        val rec = recognizer
        if (rec == null || _modelStatus != ModelStatus.VERIFIED) {
            val err = "Recognizer not ready (status=$_modelStatus, recIsNull=${rec == null})"
            Log.e(TAG, "[STT_ERROR] $err")
            return@withContext Result.failure(IllegalStateException(err))
        }

        val startTime = System.currentTimeMillis()

        try {
            // Convert 16-bit PCM ShortArray to FloatArray normalized to [-1.0f, 1.0f]
            val floatSamples = FloatArray(samples.size) { i ->
                samples[i].toFloat() / 32768.0f
            }

            // Create stream, feed waveform, decode, extract result
            val stream = rec.createStream()
            val text: String
            try {
                stream.acceptWaveform(floatSamples, sampleRate)
                rec.decode(stream)
                val result = rec.getResult(stream)
                text = result.text.trim()
            } finally {
                stream.release()
            }

            val latencyMs = System.currentTimeMillis() - startTime
            val rtf = if (audioDurationMs > 0) latencyMs.toDouble() / audioDurationMs else 0.0

            Log.i(TAG, "[STT_RESULT] result=\"$text\" latencyMs=$latencyMs RTF=${String.format("%.2f", rtf)} lang=$activeLanguage")
            Log.i(
                TAG_REAL,
                "[VAANISETU_REAL_STT] audioDurationMs=$audioDurationMs samples=${samples.size} result=\"$text\" latencyMs=$latencyMs RTF=${String.format("%.2f", rtf)} lang=$activeLanguage"
            )

            Result.success(text)
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            Log.e(TAG, "[STT_ERROR] Transcribe exception: ${e.javaClass.simpleName}: ${e.message} (latencyMs=$latencyMs)", e)
            Result.failure(e)
        }
    }

    override fun release() {
        try {
            recognizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "[VAANISETU_STT] Release notice: ${e.message}")
        }
        recognizer = null
        isInitialized = false
        _modelStatus = ModelStatus.UNAVAILABLE
        Log.i(TAG, "[VAANISETU_STT] Engine released")
    }
}
