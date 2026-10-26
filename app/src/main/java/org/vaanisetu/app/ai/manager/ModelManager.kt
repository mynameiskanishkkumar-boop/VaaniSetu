package org.vaanisetu.app.ai.manager

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.vaanisetu.app.ai.stt.SherpaOnnxSttEngine
import org.vaanisetu.app.ai.stt.SpeechToTextEngine
import org.vaanisetu.app.ai.tts.SherpaOnnxTtsEngine
import org.vaanisetu.app.ai.tts.TextToSpeechEngine
import org.vaanisetu.app.data.model.LanguageRegistry
import org.vaanisetu.app.data.model.ModelStatus
import org.vaanisetu.app.data.model.SupportedLanguage
import java.io.File

/**
 * Model Manager executing the Memory Optimization Strategy:
 * - Maintains only one active STT model and one active TTS model in RAM at any given time.
 * - Releases native JNI memory and invokes GC on language switch.
 * - Uses ModelAssetManager for asset directory management and offline model availability.
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "VAANISETU_MODELS"
    }

    val modelAssetManager = ModelAssetManager(context)

    private val _selectedLanguage = MutableStateFlow(LanguageRegistry.languages[0]) // Default: Hindi
    val selectedLanguage: StateFlow<SupportedLanguage> = _selectedLanguage

    private val _sttStatus = MutableStateFlow(ModelStatus.UNAVAILABLE)
    val sttStatus: StateFlow<ModelStatus> = _sttStatus

    private val _ttsStatus = MutableStateFlow(ModelStatus.UNAVAILABLE)
    val ttsStatus: StateFlow<ModelStatus> = _ttsStatus

    private var activeSttEngine: SpeechToTextEngine? = null
    private var activeTtsEngine: TextToSpeechEngine? = null

    suspend fun selectLanguage(langCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val lang = LanguageRegistry.getByCode(langCode)
        _selectedLanguage.value = lang

        // 1. Ensure model directories exist & extract bundled assets if any
        modelAssetManager.ensureDirectories()
        modelAssetManager.extractAssetsIfNeeded()

        // 2. STT Engine: Whisper Tiny Multilingual (shared for all 10 languages)
        if (activeSttEngine == null) {
            val stt = SherpaOnnxSttEngine(context)
            val sttInit = stt.initialize(modelAssetManager.sttModelDir, langCode)
            if (sttInit.isSuccess) {
                activeSttEngine = stt
                _sttStatus.value = stt.modelStatus
            } else {
                _sttStatus.value = ModelStatus.ERROR
            }
        } else {
            // Already initialized, switch target language token
            activeSttEngine?.setLanguage(langCode)
            _sttStatus.value = activeSttEngine?.modelStatus ?: ModelStatus.UNAVAILABLE
        }

        // 3. TTS Engine: Unload old and initialize new for target language
        activeTtsEngine?.release()
        activeTtsEngine = null

        val ttsDir = modelAssetManager.ttsModelDir(langCode)
        val tts = SherpaOnnxTtsEngine(context)
        val ttsInit = tts.initialize(ttsDir, langCode)
        if (ttsInit.isSuccess) {
            activeTtsEngine = tts
            _ttsStatus.value = tts.modelStatus
        } else {
            _ttsStatus.value = ModelStatus.ERROR
        }

        Log.i(TAG, "Selected language: $langCode (STT status=${_sttStatus.value}, TTS status=${_ttsStatus.value})")
        Result.success(Unit)
    }

    fun getSttEngine(): SpeechToTextEngine? = activeSttEngine
    fun getTtsEngine(): TextToSpeechEngine? = activeTtsEngine

    fun unloadModels() {
        activeSttEngine?.release()
        activeSttEngine = null

        activeTtsEngine?.release()
        activeTtsEngine = null

        _sttStatus.value = ModelStatus.UNAVAILABLE
        _ttsStatus.value = ModelStatus.UNAVAILABLE

        // Recommend GC for immediate native buffer reclamation
        System.gc()
    }
}
