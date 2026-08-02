package com.taskerlite.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.taskerlite.data.GeoLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Best-effort approximate location for solar calculations.
 * Prefers last-known network/GPS (fast, no continuous tracking).
 */
object LocationHelper {
    private const val TAG = "LocationHelper"
    private const val FRESH_MS = 30L * 24 * 60 * 60 * 1000 // 30 days OK for city-level

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Returns a usable [GeoLocation], preferring [cached] if still reasonably fresh,
     * otherwise querying the platform location providers.
     */
    suspend fun resolve(
        context: Context,
        cached: GeoLocation?,
    ): GeoLocation? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cached != null && cached.isValid() && now - cached.updatedAtMs < FRESH_MS) {
            return@withContext cached
        }
        if (!hasLocationPermission(context)) {
            return@withContext cached?.takeIf { it.isValid() }
        }
        val fresh = readLastKnown(context)
            ?: withTimeoutOrNull(8_000) { requestSingleUpdate(context) }
        fresh ?: cached?.takeIf { it.isValid() }
    }

    @SuppressLint("MissingPermission")
    private fun readLastKnown(context: Context): GeoLocation? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        var best: Location? = null
        for (p in providers) {
            if (!lm.isProviderEnabled(p) && p != LocationManager.PASSIVE_PROVIDER) continue
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        return best?.toGeo("last_known")
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleUpdate(context: Context): GeoLocation? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            else -> return null
        }
        return suspendCancellableCoroutine { cont ->
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { lm.removeUpdates(this) }
                    if (cont.isActive) cont.resume(location.toGeo(provider))
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            cont.invokeOnCancellation {
                runCatching { lm.removeUpdates(listener) }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    lm.getCurrentLocation(
                        provider,
                        null,
                        context.mainExecutor,
                    ) { location ->
                        if (cont.isActive) {
                            cont.resume(location?.toGeo(provider))
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, context.mainLooper)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Location request failed", e)
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun Location.toGeo(source: String): GeoLocation =
        GeoLocation(
            latitude = latitude,
            longitude = longitude,
            updatedAtMs = System.currentTimeMillis(),
            source = source,
        )
}
