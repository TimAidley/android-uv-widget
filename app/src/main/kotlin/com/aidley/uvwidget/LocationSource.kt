package com.aidley.uvwidget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

/**
 * Reads the device's location using the platform LocationManager.
 *
 * Deliberately avoids Google Play Services so the app has no proprietary dependency and works
 * on any Android device. UV forecasts vary over tens of kilometres, so a coarse, possibly
 * slightly stale fix is entirely good enough — there is no need to spin up GPS.
 */
object LocationSource {

    private const val TAG = "LocationSource"

    /** Older fixes than this are ignored when picking the best last-known location. */
    private const val MAX_FIX_AGE_MILLIS = 6L * 60L * 60L * 1000L

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Best last-known fix across the available providers, or null if there is none.
     *
     * Only reliable while the app is in the foreground: from Android 10, apps without the
     * background-location permission get null here when running in the background. That is why
     * [Prefs.cachedDeviceLocation] exists — the config screen caches a fix that the background
     * refresh can reuse.
     */
    fun lastKnownLocation(context: Context, nowMillis: Long = System.currentTimeMillis()): Coordinates? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService<LocationManager>() ?: return null

        val providers = try {
            manager.getProviders(true)
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to location providers", e)
            return null
        }

        var best: Location? = null
        for (provider in providers) {
            val fix = try {
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                Log.w(TAG, "No access to provider $provider", e)
                null
            } ?: continue

            if (nowMillis - fix.time > MAX_FIX_AGE_MILLIS) continue
            if (best == null || isBetterThan(fix, best)) best = fix
        }

        return best?.let { Coordinates(it.latitude, it.longitude) }?.takeIf { it.isValid }
    }

    /**
     * Asks for a single fresh fix. Call from the foreground only; [onResult] runs on the main
     * thread and receives null if no fix arrives.
     */
    fun requestSingleUpdate(context: Context, onResult: (Coordinates?) -> Unit) {
        if (!hasLocationPermission(context)) {
            onResult(null)
            return
        }
        val manager = context.getSystemService<LocationManager>()
        if (manager == null) {
            onResult(null)
            return
        }

        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            onResult(lastKnownLocation(context))
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getCurrentLocation(
                    provider,
                    null,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    onResult(location?.let { Coordinates(it.latitude, it.longitude) }?.takeIf { it.isValid }
                        ?: lastKnownLocation(context))
                }
            } else {
                // Implemented explicitly rather than as a lambda: before API 29 the callbacks
                // below are abstract on the device, so a SAM-converted lambda would blow up with
                // AbstractMethodError the first time the platform reported a status change.
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        onResult(
                            Coordinates(location.latitude, location.longitude).takeIf { it.isValid }
                                ?: lastKnownLocation(context)
                        )
                    }

                    @Deprecated("Required by LocationListener before API 29")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) = Unit
                }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, context.mainLooper)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location update refused", e)
            onResult(null)
        }
    }

    /** Prefer the more accurate fix, falling back to the more recent one. */
    private fun isBetterThan(candidate: Location, current: Location): Boolean = when {
        candidate.hasAccuracy() && current.hasAccuracy() && candidate.accuracy != current.accuracy ->
            candidate.accuracy < current.accuracy
        else -> candidate.time > current.time
    }
}
