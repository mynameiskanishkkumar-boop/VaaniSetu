package org.vaanisetu.app.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Status and freshness of acquired GPS/GNSS location.
 */
enum class LocationFreshness(val value: Byte) {
    UNAVAILABLE(0x00),
    FRESH(0x01),
    LAST_KNOWN(0x02);

    companion object {
        fun fromByte(b: Byte): LocationFreshness = entries.find { it.value == b } ?: UNAVAILABLE
    }
}

/**
 * Source Location Data Model for VaaniSetu.
 * Represents the GPS/GNSS coordinates of the SENDER phone at the moment of transmission.
 * 
 * Works 100% offline without any cellular data, Internet connection, or cloud APIs.
 */
data class SourceLocationInfo(
    val hasLocation: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Float = 0.0f,
    val locationTimestamp: Long = 0L,
    val locationFreshness: LocationFreshness = LocationFreshness.UNAVAILABLE,
    val locationSource: String = "GNSS", // "GNSS", "GPS", "NETWORK", "CACHE"
    val altitudeMeters: Double = 0.0,
    val address: String? = null // Optional offline human-readable address if genuine local geocoding exists
) {
    /**
     * Validates whether the coordinates are within valid geographic bounds.
     */
    fun isValid(): Boolean {
        if (!hasLocation) return false
        if (latitude < -90.0 || latitude > 90.0) return false
        if (longitude < -180.0 || longitude > 180.0) return false
        // Exclude exact (0.0, 0.0) null-island unless explicitly marked valid with high accuracy
        if (latitude == 0.0 && longitude == 0.0 && accuracyMeters == 0.0f) return false
        return true
    }

    /**
     * Calculates the age of the location fix in seconds relative to a given timestamp.
     */
    fun getAgeSeconds(currentTimestamp: Long = System.currentTimeMillis()): Long {
        if (locationTimestamp <= 0L) return -1L
        val diffMs = currentTimestamp - locationTimestamp
        return if (diffMs >= 0) diffMs / 1000L else 0L
    }

    /**
     * Formats coordinates in standard decimal degrees with directional hemisphere indicators.
     * e.g., "12.971599° N, 77.594566° E"
     */
    fun formatCoordinates(): String {
        if (!isValid()) return "Location Unavailable"
        val latDir = if (latitude >= 0) "N" else "S"
        val lonDir = if (longitude >= 0) "E" else "W"
        return String.format(Locale.US, "%.6f° %s, %.6f° %s", Math.abs(latitude), latDir, Math.abs(longitude), lonDir)
    }

    /**
     * Formats raw decimal coordinates suitable for clipboard copying or mapping apps.
     * e.g., "12.971599, 77.594566"
     */
    fun formatRawCoordinates(): String {
        if (!isValid()) return ""
        return String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
    }

    /**
     * Formats accuracy in human-readable meters.
     */
    fun formatAccuracy(): String {
        return if (accuracyMeters > 0f) {
            String.format(Locale.US, "±%.1fm", accuracyMeters)
        } else {
            "Unknown accuracy"
        }
    }

    /**
     * Formats location acquisition timestamp.
     */
    fun formatTimestamp(): String {
        if (locationTimestamp <= 0L) return "N/A"
        val sdf = SimpleDateFormat("HH:mm:ss (yyyy-MM-dd)", Locale.getDefault())
        return sdf.format(Date(locationTimestamp))
    }

    /**
     * Diagnostic summary string for logging and verification.
     */
    fun getDiagnosticSummary(): String {
        return if (hasLocation) {
            "lat=${String.format(Locale.US, "%.6f", latitude)}, lon=${String.format(Locale.US, "%.6f", longitude)}, acc=${formatAccuracy()}, freshness=${locationFreshness.name}, source=$locationSource, age=${getAgeSeconds()}s"
        } else {
            "hasLocation=false, status=UNAVAILABLE"
        }
    }
}
