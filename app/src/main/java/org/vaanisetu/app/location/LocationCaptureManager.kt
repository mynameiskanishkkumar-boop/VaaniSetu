package org.vaanisetu.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vaanisetu.app.data.model.LocationFreshness
import org.vaanisetu.app.data.model.SourceLocationInfo
import java.util.Locale

/**
 * Production-ready Offline Location Manager for VaaniSetu.
 * 
 * Acquires genuine on-device GPS/GNSS coordinates via native Android LocationManager.
 * Operates 100% offline with zero cloud, Google Play Services, or Internet requirements.
 * 
 * Non-blocking: If GPS has no fix or is slow, it provides last-known location or gracefully
 * falls back to unavailable without blocking voice transmissions.
 */
class LocationCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "VAANISETU_LOCATION"
        private const val FRESHNESS_THRESHOLD_MS = 30_000L // Fixes within 30s considered FRESH
        private const val DEFAULT_TIMEOUT_MS = 6_000L // 6s acquisition timeout
    }

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private var cachedLocationInfo: SourceLocationInfo? = null
    private val locationLock = Any()

    private val _lastCapturedLocation = MutableStateFlow<SourceLocationInfo>(SourceLocationInfo())
    val lastCapturedLocation: StateFlow<SourceLocationInfo> = _lastCapturedLocation

    /**
     * Checks if fine or coarse location permissions are granted.
     */
    fun hasLocationPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Checks if GPS hardware provider is enabled on device.
     */
    fun isGpsEnabled(): Boolean {
        return try {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check GPS provider status: ${e.message}")
            false
        }
    }

    /**
     * Checks if any location provider (GPS or Network/Cell) is enabled.
     */
    fun isAnyLocationProviderEnabled(): Boolean {
        return try {
            val isGps = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            val isNetwork = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            isGps || isNetwork
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Acquires the current GNSS fix with a coroutine timeout.
     * If a fresh fix cannot be acquired before timeout, falls back to the best last-known fix.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(timeoutMs: Long = DEFAULT_TIMEOUT_MS): SourceLocationInfo = withContext(Dispatchers.IO) {
        if (!hasLocationPermissions()) {
            Log.w(TAG, "[LOCATION_PERMISSION_DENIED] Location permissions not granted")
            return@withContext SourceLocationInfo(
                hasLocation = false,
                locationFreshness = LocationFreshness.UNAVAILABLE,
                locationSource = "PERMISSION_DENIED"
            )
        }

        if (!isAnyLocationProviderEnabled()) {
            Log.w(TAG, "[LOCATION_DISABLED] GPS/Location providers are turned OFF in system settings")
            val lastKnown = getLastKnownLocation()
            if (lastKnown.isValid()) {
                Log.i(TAG, "[LOCATION_LAST_KNOWN] Using cached fix due to providers OFF: ${lastKnown.getDiagnosticSummary()}")
                return@withContext lastKnown
            }
            return@withContext SourceLocationInfo(
                hasLocation = false,
                locationFreshness = LocationFreshness.UNAVAILABLE,
                locationSource = "PROVIDERS_DISABLED"
            )
        }

        Log.i(TAG, "[LOCATION_REQUEST] Requesting GNSS location fix (timeout=${timeoutMs}ms)")

        var freshFix: Location? = null
        val completer = CompletableDeferred<Location?>()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.i(
                    TAG,
                    "[LOCATION_FIX] Received fix from ${location.provider}: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m, time=${location.time}"
                )
                completer.complete(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {
                Log.i(TAG, "[LOCATION_PROVIDER_ENABLED] $provider")
            }
            override fun onProviderDisabled(provider: String) {
                Log.w(TAG, "[LOCATION_PROVIDER_DISABLED] $provider")
            }
        }

        withContext(Dispatchers.Main) {
            try {
                // Request updates from GPS_PROVIDER
                if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        500L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                }

                // Request updates from NETWORK_PROVIDER as fallback if available
                if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        500L,
                        0f,
                        listener,
                        Looper.getMainLooper()
                    )
                }
                Unit
            } catch (e: Exception) {
                Log.e(TAG, "[LOCATION_ERROR] Error requesting location updates: ${e.message}", e)
                completer.complete(null)
                Unit
            }
        }

        try {
            freshFix = withTimeoutOrNull(timeoutMs) {
                completer.await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[LOCATION_TIMEOUT] Exception waiting for fix: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) {
                try {
                    locationManager?.removeUpdates(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing location updates: ${e.message}")
                }
            }
        }

        if (freshFix != null) {
            val isRecent = (System.currentTimeMillis() - freshFix.time) < FRESHNESS_THRESHOLD_MS
            val freshness = if (isRecent) LocationFreshness.FRESH else LocationFreshness.LAST_KNOWN
            val providerName = freshFix.provider?.uppercase(Locale.US) ?: "GNSS"

            val info = SourceLocationInfo(
                hasLocation = true,
                latitude = freshFix.latitude,
                longitude = freshFix.longitude,
                accuracyMeters = freshFix.accuracy,
                locationTimestamp = freshFix.time,
                locationFreshness = freshness,
                locationSource = providerName,
                altitudeMeters = if (freshFix.hasAltitude()) freshFix.altitude else 0.0,
                address = null
            )

            synchronized(locationLock) {
                cachedLocationInfo = info
            }
            _lastCapturedLocation.value = info
            Log.i(TAG, "[LOCATION_SUCCESS] Fresh fix: ${info.getDiagnosticSummary()}")
            return@withContext info
        }

        // Fresh fix timed out — Fall back to last known location
        Log.w(TAG, "[LOCATION_TIMEOUT] Acquisition timed out after ${timeoutMs}ms. Falling back to last known location.")
        val lastKnown = getLastKnownLocation()
        if (lastKnown.isValid()) {
            Log.i(TAG, "[LOCATION_LAST_KNOWN] Using last-known fix: ${lastKnown.getDiagnosticSummary()}")
            return@withContext lastKnown
        }

        Log.w(TAG, "[LOCATION_UNAVAILABLE] No GPS fix or last-known location available")
        val unavailableInfo = SourceLocationInfo(
            hasLocation = false,
            locationFreshness = LocationFreshness.UNAVAILABLE,
            locationSource = "NO_FIX"
        )
        _lastCapturedLocation.value = unavailableInfo
        return@withContext unavailableInfo
    }

    /**
     * Inspects the device's native last-known location and internal cache.
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(): SourceLocationInfo {
        if (!hasLocationPermissions()) {
            return SourceLocationInfo(
                hasLocation = false,
                locationFreshness = LocationFreshness.UNAVAILABLE,
                locationSource = "PERMISSION_DENIED"
            )
        }

        var bestLocation: Location? = null

        try {
            val providers = listOfNotNull(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )

            for (provider in providers) {
                try {
                    val loc = locationManager?.getLastKnownLocation(provider) ?: continue
                    if (bestLocation == null || loc.time > bestLocation.time) {
                        bestLocation = loc
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying last known location: ${e.message}")
        }

        val cached = synchronized(locationLock) { cachedLocationInfo }
        if (bestLocation != null) {
            val ageMs = System.currentTimeMillis() - bestLocation.time
            val isFresh = ageMs < FRESHNESS_THRESHOLD_MS
            val freshness = if (isFresh) LocationFreshness.FRESH else LocationFreshness.LAST_KNOWN

            val info = SourceLocationInfo(
                hasLocation = true,
                latitude = bestLocation.latitude,
                longitude = bestLocation.longitude,
                accuracyMeters = bestLocation.accuracy,
                locationTimestamp = bestLocation.time,
                locationFreshness = freshness,
                locationSource = bestLocation.provider?.uppercase(Locale.US) ?: "LAST_KNOWN",
                altitudeMeters = if (bestLocation.hasAltitude()) bestLocation.altitude else 0.0,
                address = null
            )

            if (cached == null || info.locationTimestamp >= cached.locationTimestamp) {
                synchronized(locationLock) { cachedLocationInfo = info }
                _lastCapturedLocation.value = info
                return info
            }
        }

        if (cached != null && cached.isValid()) {
            val ageMs = System.currentTimeMillis() - cached.locationTimestamp
            val updatedFreshness = if (ageMs < FRESHNESS_THRESHOLD_MS) LocationFreshness.FRESH else LocationFreshness.LAST_KNOWN
            return cached.copy(locationFreshness = updatedFreshness)
        }

        return SourceLocationInfo(
            hasLocation = false,
            locationFreshness = LocationFreshness.UNAVAILABLE,
            locationSource = "NONE"
        )
    }

    /**
     * Manually update the cached location (e.g. for testing).
     */
    fun setCachedLocation(info: SourceLocationInfo) {
        synchronized(locationLock) {
            cachedLocationInfo = info
        }
        _lastCapturedLocation.value = info
    }
}
