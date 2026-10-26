package org.vaanisetu.app.telemetry

import android.content.Context
import android.os.Debug
import org.vaanisetu.app.data.model.TelemetryData
import kotlin.math.min

/**
 * Benchmarking Engine for live telemetry, WER evaluation, RTF, and resource profiling.
 */
class BenchmarkEngine(private val context: Context) {

    /**
     * Calculates Word Error Rate (WER) using Levenshtein distance on words.
     * Formula: WER = (Substitutions + Deletions + Insertions) / Total Reference Words
     */
    fun calculateWer(groundTruth: String, prediction: String): Double {
        val refWords = groundTruth.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val hypWords = prediction.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        if (refWords.isEmpty()) {
            return if (hypWords.isEmpty()) 0.0 else 100.0
        }

        val d = Array(refWords.size + 1) { IntArray(hypWords.size + 1) }

        for (i in 0..refWords.size) d[i][0] = i
        for (j in 0..hypWords.size) d[0][j] = j

        for (i in 1..refWords.size) {
            for (j in 1..hypWords.size) {
                val cost = if (refWords[i - 1].equals(hypWords[j - 1], ignoreCase = true)) 0 else 1
                d[i][j] = min(
                    d[i - 1][j] + 1, // deletion
                    min(
                        d[i][j - 1] + 1, // insertion
                        d[i - 1][j - 1] + cost // substitution
                    )
                )
            }
        }

        val editDistance = d[refWords.size][hypWords.size]
        return (editDistance.toDouble() / refWords.size.toDouble()) * 100.0
    }

    /**
     * Samples the current native + Dalvik total PSS memory usage in Megabytes.
     */
    fun getMemoryPssMb(): Double {
        return try {
            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            memInfo.totalPss / 1024.0
        } catch (e: Exception) {
            0.0
        }
    }
}
