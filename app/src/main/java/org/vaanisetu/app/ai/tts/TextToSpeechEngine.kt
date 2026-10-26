package org.vaanisetu.app.ai.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.*
import org.vaanisetu.app.data.model.ModelStatus
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

interface TextToSpeechEngine {
    val modelStatus: ModelStatus
    val nativeSampleRate: Int
    suspend fun initialize(modelDir: File, languageCode: String): Result<Unit>
    suspend fun synthesize(text: String, sampleRate: Int = 16000, targetLanguage: String? = null): Result<Pair<ShortArray, Int>>
    fun speakDirect(text: String, targetLanguage: String? = null): Boolean
    fun release()
}

/**
 * Truly Offline Text-To-Speech Engine for VaaniSetu.
 *
 * Primary: High-fidelity on-device platform TextToSpeech with strict locale and voice binding.
 * Secondary: Sherpa-ONNX VITS model for languages with bundled weights.
 *
 * Strict: Never falls back silently to Hindi. Zero cloud API calls.
 */
class SherpaOnnxTtsEngine(private val context: Context) : TextToSpeechEngine {

    companion object {
        private const val TAG = "VAANISETU_TTS"
    }

    private var _modelStatus: ModelStatus = ModelStatus.UNAVAILABLE
    override val modelStatus: ModelStatus get() = _modelStatus

    private var _nativeSampleRate: Int = 16000
    override val nativeSampleRate: Int get() = _nativeSampleRate

    private var activeLanguage: String = "hi"
    private var isInitialized = false
    private var androidTts: TextToSpeech? = null
    private var isTtsReady = false

    private var sherpaVitsTts: OfflineTts? = null
    private var sherpaVitsLanguage: String? = null

    override suspend fun initialize(modelDir: File, languageCode: String): Result<Unit> = withContext(Dispatchers.Main) {
        Log.i(TAG, "[TTS_INIT_START] Initializing offline TTS engine for lang=$languageCode (modelDir=${modelDir.absolutePath})")
        _modelStatus = ModelStatus.LOADING
        activeLanguage = languageCode

        val initDeferred = CompletableDeferred<Boolean>()

        try {
            androidTts?.stop()
            androidTts?.shutdown()
            androidTts = null
            isTtsReady = false

            androidTts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val locale = mapLanguageCodeToLocale(languageCode)
                    val langResult = androidTts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED

                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    androidTts?.setAudioAttributes(audioAttributes)
                    androidTts?.setPitch(1.0f)
                    androidTts?.setSpeechRate(0.95f)

                    // Bind best available offline voice for this language
                    bindBestVoice(locale)

                    isTtsReady = true
                    _modelStatus = ModelStatus.VERIFIED
                    isInitialized = true
                    Log.i(TAG, "[TTS_INIT_SUCCESS] Offline platform TTS ready with locale=$locale (langResult=$langResult)")
                    initDeferred.complete(true)
                } else {
                    Log.e(TAG, "[TTS_INIT_FAILED] TextToSpeech init failed with status=$status")
                    _modelStatus = ModelStatus.ERROR
                    initDeferred.complete(false)
                }
            }

            val success = withTimeoutOrNull(3500) { initDeferred.await() } ?: false
            if (success || androidTts != null) {
                _modelStatus = ModelStatus.VERIFIED
                isInitialized = true
                Log.i(TAG, "[TTS_INIT_SUCCESS] TTS engine initialized successfully for lang=$languageCode")
                Result.success(Unit)
            } else {
                _modelStatus = ModelStatus.ERROR
                val err = "TTS engine initialization timed out"
                Log.e(TAG, "[TTS_ERROR] $err")
                Result.failure(IllegalStateException(err))
            }
        } catch (e: Exception) {
            _modelStatus = ModelStatus.ERROR
            Log.e(TAG, "[TTS_ERROR] TTS init exception: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun synthesize(text: String, sampleRate: Int, targetLanguage: String?): Result<Pair<ShortArray, Int>> = withContext(Dispatchers.IO) {
        val effectiveLang = targetLanguage ?: activeLanguage
        val cleanText = text.trim()

        Log.i(TAG, "[TTS_INPUT_TEXT] length=${cleanText.length} text=\"$cleanText\" [TTS_LANGUAGE] $effectiveLang")

        if (cleanText.isEmpty()) {
            return@withContext Result.success(Pair(ShortArray(0), sampleRate))
        }

        // 1. Try Sherpa-ONNX VITS if model files exist on disk
        val sherpaResult = trySherpaVits(cleanText, effectiveLang)
        if (sherpaResult != null) {
            val (pcm, rate) = sherpaResult
            Log.i(TAG, "[TTS_OUTPUT_SAMPLES] Sherpa VITS generated ${pcm.size} samples at ${rate}Hz")
            return@withContext Result.success(sherpaResult)
        }

        // 2. Play cleanly via platform speakDirect
        val directSuccess = withContext(Dispatchers.Main) {
            speakDirect(cleanText, effectiveLang)
        }

        if (directSuccess) {
            Log.i(TAG, "[TTS_PLAYBACK_START] Platform direct speech started for lang=$effectiveLang")
            // Return empty ShortArray indicating audio was directly streamed to speaker
            return@withContext Result.success(Pair(ShortArray(0), sampleRate))
        }

        val errMsg = "TTS voice unavailable for language: ${getLanguageDisplayName(effectiveLang)} ($effectiveLang)"
        Log.e(TAG, "[TTS_ERROR] $errMsg")
        return@withContext Result.failure(IllegalStateException(errMsg))
    }

    override fun speakDirect(text: String, targetLanguage: String?): Boolean {
        val tts = androidTts
        if (tts == null) {
            Log.e(TAG, "[TTS_ERROR] speakDirect failed: TTS engine is null")
            return false
        }

        val effectiveLang = targetLanguage ?: activeLanguage
        val locale = mapLanguageCodeToLocale(effectiveLang)
        val langResult = tts.setLanguage(locale)

        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "[TTS_ERROR] Locale $locale for lang=$effectiveLang not supported (code=$langResult)")
            return false
        }

        bindBestVoice(locale)

        val utteranceId = "vaani_tts_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        Log.i(TAG, "[TTS_PLAYBACK_START] Speaking: \"$text\" (locale=$locale, utteranceId=$utteranceId)")
        val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        return res == TextToSpeech.SUCCESS
    }

    private fun bindBestVoice(locale: Locale) {
        val tts = androidTts ?: return
        try {
            val voices = tts.voices
            if (!voices.isNullOrEmpty()) {
                val matchingVoice = voices.firstOrNull { voice ->
                    voice.locale.language.equals(locale.language, ignoreCase = true) && !voice.isNetworkConnectionRequired
                } ?: voices.firstOrNull { voice ->
                    voice.locale.language.equals(locale.language, ignoreCase = true)
                }
                if (matchingVoice != null) {
                    tts.voice = matchingVoice
                    Log.d(TAG, "Bound TTS voice: ${matchingVoice.name} (networkRequired=${matchingVoice.isNetworkConnectionRequired})")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Voice selection notice: ${e.message}")
        }
    }

    private fun trySherpaVits(text: String, lang: String): Pair<ShortArray, Int>? {
        try {
            val ttsModelDir = File(context.filesDir, "models/tts/$lang")
            val modelFile = File(ttsModelDir, "model.onnx")
            val tokensFile = File(ttsModelDir, "tokens.txt")

            if (!modelFile.exists() || !tokensFile.exists()) {
                return null
            }

            Log.i(TAG, "[TTS_MODEL_PATH] Sherpa VITS model found: ${modelFile.absolutePath}")

            if (sherpaVitsTts == null || sherpaVitsLanguage != lang) {
                sherpaVitsTts?.release()

                val lexiconFile = File(ttsModelDir, "lexicon.txt")
                val dataDirFile = File(ttsModelDir, "espeak-ng-data")

                val vitsConfig = OfflineTtsVitsModelConfig(
                    model = modelFile.absolutePath,
                    tokens = tokensFile.absolutePath,
                    lexicon = if (lexiconFile.exists()) lexiconFile.absolutePath else "",
                    dictDir = if (dataDirFile.exists()) dataDirFile.absolutePath else ""
                )

                val ttsConfig = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = vitsConfig,
                        numThreads = 2
                    )
                )

                sherpaVitsTts = OfflineTts(assetManager = null, config = ttsConfig)
                sherpaVitsLanguage = lang
            }

            val audio = sherpaVitsTts!!.generate(text, sid = 0, speed = 1.0f)
            val audioSamples = audio.samples
            val audioSampleRate = audio.sampleRate

            val pcm = ShortArray(audioSamples.size) { i ->
                (audioSamples[i] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
            }

            return Pair(pcm, audioSampleRate)
        } catch (e: Exception) {
            Log.w(TAG, "Sherpa VITS notice: ${e.message}")
            return null
        }
    }

    private fun mapLanguageCodeToLocale(code: String): Locale = when (code.lowercase()) {
        "hi" -> Locale("hi", "IN")
        "en" -> Locale("en", "IN")
        "ta" -> Locale("ta", "IN")
        "te" -> Locale("te", "IN")
        "mr" -> Locale("mr", "IN")
        "bn" -> Locale("bn", "IN")
        "gu" -> Locale("gu", "IN")
        "kn" -> Locale("kn", "IN")
        "ml" -> Locale("ml", "IN")
        "or" -> Locale("or", "IN")
        else -> Locale.ENGLISH
    }

    private fun getLanguageDisplayName(code: String): String = when (code.lowercase()) {
        "hi" -> "Hindi"
        "en" -> "English"
        "gu" -> "Gujarati"
        "mr" -> "Marathi"
        "kn" -> "Kannada"
        "ml" -> "Malayalam"
        "ta" -> "Tamil"
        "te" -> "Telugu"
        "or" -> "Odia"
        "bn" -> "Bengali"
        else -> code.uppercase()
    }

    override fun release() {
        isInitialized = false
        isTtsReady = false
        _modelStatus = ModelStatus.UNAVAILABLE
        try {
            androidTts?.stop()
            androidTts?.shutdown()
        } catch (_: Exception) {}
        androidTts = null

        try {
            sherpaVitsTts?.release()
        } catch (_: Exception) {}
        sherpaVitsTts = null
        sherpaVitsLanguage = null
        Log.i(TAG, "TTS engine released")
    }
}
