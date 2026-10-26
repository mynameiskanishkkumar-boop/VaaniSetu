package org.vaanisetu.app.ai.manager

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Manages extraction and availability of local AI model files.
 * Models are stored in context.filesDir/models/
 * 
 * STT: Whisper Tiny Multilingual INT8 (single model for all 10 languages)
 * TTS: Language-specific offline voices / VITS models
 */
class ModelAssetManager(private val context: Context) {

    companion object {
        private const val TAG = "VAANISETU_MODELS"
        private const val MODELS_DIR = "models"
        private const val STT_DIR = "stt"
        private const val TTS_DIR = "tts"
        private const val EXTRACTED_MARKER = ".extracted"
    }

    private val modelsBaseDir: File get() = File(context.filesDir, MODELS_DIR)
    val sttModelDir: File get() = File(modelsBaseDir, STT_DIR)
    fun ttsModelDir(langCode: String): File = File(File(modelsBaseDir, TTS_DIR), langCode)

    /**
     * Check if STT model files are available on disk.
     */
    fun isSttModelAvailable(): Boolean {
        val encoder = File(sttModelDir, "tiny-encoder.int8.onnx")
        val decoder = File(sttModelDir, "tiny-decoder.int8.onnx")
        val tokens = File(sttModelDir, "tiny-tokens.txt")
        return encoder.exists() && encoder.length() > 5_000_000 &&
               decoder.exists() && decoder.length() > 50_000_000 &&
               tokens.exists() && tokens.length() > 100_000
    }

    /**
     * Check if TTS model files are available for a specific language.
     */
    fun isTtsModelAvailable(langCode: String): Boolean {
        val dir = ttsModelDir(langCode)
        if (!dir.exists()) return false
        val model = File(dir, "model.onnx")
        val tokens = File(dir, "tokens.txt")
        return model.exists() && model.length() > 0 &&
               tokens.exists() && tokens.length() > 0
    }

    fun getSttModelPath(filename: String): String {
        return File(sttModelDir, filename).absolutePath
    }

    fun getTtsModelPath(langCode: String, filename: String): String {
        return File(ttsModelDir(langCode), filename).absolutePath
    }

    /**
     * Extract models from APK assets to internal storage if not already extracted.
     */
    suspend fun extractAssetsIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureDirectories()

            val sttMarker = File(sttModelDir, EXTRACTED_MARKER)
            if (!isSttModelAvailable() || !sttMarker.exists()) {
                Log.i(TAG, "[STT_MODEL_EXTRACT_START] Extracting Whisper Tiny models from APK assets...")
                
                val encoderOk = copyAssetFileDirect("models/stt/tiny-encoder.int8.onnx", File(sttModelDir, "tiny-encoder.int8.onnx"))
                val decoderOk = copyAssetFileDirect("models/stt/tiny-decoder.int8.onnx", File(sttModelDir, "tiny-decoder.int8.onnx"))
                val tokensOk = copyAssetFileDirect("models/stt/tiny-tokens.txt", File(sttModelDir, "tiny-tokens.txt"))

                if (encoderOk && decoderOk && tokensOk) {
                    sttMarker.createNewFile()
                    Log.i(TAG, "[STT_MODEL_EXTRACT_SUCCESS] STT model files ready at ${sttModelDir.absolutePath}")
                } else {
                    Log.e(TAG, "[STT_MODEL_EXTRACT_FAILED] Failed to copy one or more STT files: encoder=$encoderOk decoder=$decoderOk tokens=$tokensOk")
                }
            } else {
                Log.i(TAG, "[STT_MODEL_EXTRACT_SKIPPED] Model files already verified at ${sttModelDir.absolutePath}")
            }

            // Extract any bundled TTS assets
            val ttsLangs = listOf("hi", "en", "bn", "ta", "mr", "te", "gu", "kn", "ml", "or")
            for (lang in ttsLangs) {
                val langDir = ttsModelDir(lang)
                val marker = File(langDir, EXTRACTED_MARKER)
                if (!marker.exists()) {
                    try {
                        copyAssetFileDirect("models/tts/$lang/model.onnx", File(langDir, "model.onnx"))
                        copyAssetFileDirect("models/tts/$lang/tokens.txt", File(langDir, "tokens.txt"))
                        marker.createNewFile()
                    } catch (_: Exception) {}
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model extraction exception: ${e.message}", e)
            false
        }
    }

    /**
     * Copy a single file from assets directly to target file with atomic tmp file.
     */
    private fun copyAssetFileDirect(assetPath: String, targetFile: File): Boolean {
        return try {
            val inputStream: InputStream = context.assets.open(assetPath)
            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            
            if (tempFile.exists() && tempFile.length() > 0) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                val renamed = tempFile.renameTo(targetFile)
                Log.i(TAG, "Copied asset $assetPath -> ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                renamed
            } else {
                Log.e(TAG, "Failed: temp file is empty or not created for $assetPath")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Asset $assetPath not available in APK: ${e.message}")
            false
        }
    }

    /**
     * Ensure model directories exist.
     */
    fun ensureDirectories() {
        sttModelDir.mkdirs()
        val ttsLangs = listOf("hi", "en", "bn", "ta", "mr", "te", "gu", "kn", "ml", "or")
        for (lang in ttsLangs) {
            ttsModelDir(lang).mkdirs()
        }
    }

    fun getDiagnosticSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Model Availability ===")
        sb.appendLine("STT (Whisper Tiny): ${if (isSttModelAvailable()) "AVAILABLE" else "NOT FOUND"}")
        sb.appendLine("STT path: ${sttModelDir.absolutePath}")
        
        val ttsLangs = listOf("hi", "en", "bn", "ta", "mr", "te", "gu", "kn", "ml", "or")
        for (lang in ttsLangs) {
            val available = isTtsModelAvailable(lang)
            sb.appendLine("TTS [$lang]: ${if (available) "AVAILABLE" else "NOT FOUND"} (${ttsModelDir(lang).absolutePath})")
        }
        return sb.toString()
    }
}
