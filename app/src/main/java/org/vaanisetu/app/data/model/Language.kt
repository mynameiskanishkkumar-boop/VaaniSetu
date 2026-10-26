package org.vaanisetu.app.data.model

/**
 * Model readiness status following strict evaluation guidelines.
 */
enum class ModelStatus {
    VERIFIED,
    NEEDS_VALIDATION,
    UNAVAILABLE,
    LOADING,
    ERROR
}

/**
 * Representation of one of the 10 mandated languages for SIH 2026.
 */
data class SupportedLanguage(
    val code: String,              // e.g. "hi", "en", "ta"
    val displayName: String,       // e.g. "Hindi"
    val nativeName: String,        // e.g. "हिन्दी"
    val sttModelName: String,      // e.g. "Sherpa-ONNX IndicZipformer / Whisper Tiny"
    val sttStatus: ModelStatus,    // Verification status
    val ttsModelName: String,      // e.g. "Sherpa-ONNX VITS Hindi"
    val ttsStatus: ModelStatus     // Verification status
)

object LanguageRegistry {
    val languages: List<SupportedLanguage> = listOf(
        SupportedLanguage(
            code = "hi",
            displayName = "Hindi",
            nativeName = "हिन्दी",
            sttModelName = "Sherpa IndicZipformer INT8",
            sttStatus = ModelStatus.VERIFIED,
            ttsModelName = "Sherpa VITS Hindi INT8",
            ttsStatus = ModelStatus.VERIFIED
        ),
        SupportedLanguage(
            code = "en",
            displayName = "English (Indian)",
            nativeName = "English",
            sttModelName = "Sherpa Zipformer-en INT8",
            sttStatus = ModelStatus.VERIFIED,
            ttsModelName = "Piper VITS en-US INT8",
            ttsStatus = ModelStatus.VERIFIED
        ),
        SupportedLanguage(
            code = "bn",
            displayName = "Bengali",
            nativeName = "বাংলা",
            sttModelName = "Sherpa Zipformer-bn INT8",
            sttStatus = ModelStatus.VERIFIED,
            ttsModelName = "Sherpa VITS Bengali INT8",
            ttsStatus = ModelStatus.VERIFIED
        ),
        SupportedLanguage(
            code = "ta",
            displayName = "Tamil",
            nativeName = "தமிழ்",
            sttModelName = "Sherpa Zipformer-ta INT8",
            sttStatus = ModelStatus.VERIFIED,
            ttsModelName = "Sherpa VITS Tamil INT8",
            ttsStatus = ModelStatus.VERIFIED
        ),
        SupportedLanguage(
            code = "mr",
            displayName = "Marathi",
            nativeName = "मराठी",
            sttModelName = "Whisper-Tiny Multilingual INT8",
            sttStatus = ModelStatus.NEEDS_VALIDATION,
            ttsModelName = "IndicTTS VITS Marathi / eSpeak",
            ttsStatus = ModelStatus.NEEDS_VALIDATION
        ),
        SupportedLanguage(
            code = "te",
            displayName = "Telugu",
            nativeName = "తెలుగు",
            sttModelName = "Whisper-Tiny Multilingual INT8",
            sttStatus = ModelStatus.NEEDS_VALIDATION,
            ttsModelName = "Sherpa VITS Telugu INT8",
            ttsStatus = ModelStatus.NEEDS_VALIDATION
        ),
        SupportedLanguage(
            code = "gu",
            displayName = "Gujarati",
            nativeName = "ગુજરાતી",
            sttModelName = "Whisper-Tiny Multilingual INT8",
            sttStatus = ModelStatus.NEEDS_VALIDATION,
            ttsModelName = "IndicTTS Gujarati / eSpeak",
            ttsStatus = ModelStatus.NEEDS_VALIDATION
        ),
        SupportedLanguage(
            code = "kn",
            displayName = "Kannada",
            nativeName = "ಕನ್ನಡ",
            sttModelName = "Whisper-Tiny Multilingual INT8",
            sttStatus = ModelStatus.NEEDS_VALIDATION,
            ttsModelName = "IndicTTS Kannada / eSpeak",
            ttsStatus = ModelStatus.NEEDS_VALIDATION
        ),
        SupportedLanguage(
            code = "ml",
            displayName = "Malayalam",
            nativeName = "മലയാളം",
            sttModelName = "Whisper-Tiny Multilingual INT8",
            sttStatus = ModelStatus.NEEDS_VALIDATION,
            ttsModelName = "Sherpa VITS Malayalam INT8",
            ttsStatus = ModelStatus.NEEDS_VALIDATION
        ),
        SupportedLanguage(
            code = "or",
            displayName = "Odia",
            nativeName = "ଓଡ଼ିଆ",
            sttModelName = "Whisper-Tiny Multilingual INT8",
            sttStatus = ModelStatus.NEEDS_VALIDATION,
            ttsModelName = "IndicTTS Odia / eSpeak",
            ttsStatus = ModelStatus.NEEDS_VALIDATION
        )
    )

    fun getByCode(code: String): SupportedLanguage {
        return languages.find { it.code.equals(code, ignoreCase = true) } ?: languages[0]
    }
}
