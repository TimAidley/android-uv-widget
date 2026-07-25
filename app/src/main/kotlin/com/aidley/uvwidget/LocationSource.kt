package com.aidley.uvwidget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import java.util.concurrent.atomic.AtomicBoolean

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

    /** Longest to wait for a fresh fix before falling back to the last known one. */
    private const val DEFAULT_TIMEOUT_MILLIS = 10_000L

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
     * thread, exactly once, and receives null if no fix arrives.
     *
     * The platform's own timeout for a current-location request is around thirty seconds, which
     * is far longer than anyone will sit and watch. [timeoutMillis] caps the wait and falls back
     * to the last known fix instead — for a forecast covering tens of kilometres, a slightly old
     * position is worth far more than a precise one that arrives half a minute late.
     */
    fun requestSingleUpdate(
        context: Context,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        onResult: (Coordinates?) -> Unit
    ) {
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

        val handler = Handler(Looper.getMainLooper())
        val delivered = AtomicBoolean(false)
        val cancellation = CancellationSignal()
        var legacyListener: LocationListener? = null

        // Every path below funnels through here, so the caller is called back exactly once
        // however the race between the fresh fix and the timeout turns out.
        fun deliver(coordinates: Coordinates?) {
            if (!delivered.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            legacyListener?.let { listener ->
                runCatching { manager.removeUpdates(listener) }
                    .onFailure { Log.w(TAG, "Could not remove location updates", it) }
            }
            onResult(coordinates)
        }

        handler.postDelayed({
            // Out of time: take whatever the providers had already, rather than keep waiting.
            cancellation.cancel()
            deliver(lastKnownLocation(context))
        }, timeoutMillis)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getCurrentLocation(
                    provider,
                    cancellation,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    deliver(location?.toCoordinates() ?: lastKnownLocation(context))
                }
            } else {
                // Implemented explicitly rather than as a lambda: before API 29 the callbacks
                // below are abstract on the device, so a SAM-converted lambda would blow up with
                // AbstractMethodError the first time the platform reported a status change.
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        deliver(location.toCoordinates() ?: lastKnownLocation(context))
                    }

                    @Deprecated("Required by LocationListener before API 29")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) = Unit
                }
                legacyListener = listener
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, context.mainLooper)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location update refused", e)
            deliver(null)
        }
    }

    private fun Location.toCoordinates(): Coordinates? =
        Coordinates(latitude, longitude).takeIf { it.isValid }

    /** Prefer the more accurate fix, falling back to the more recent one. */
    private fun isBetterThan(candidate: Location, current: Location): Boolean = when {
        candidate.hasAccuracy() && current.hasAccuracy() && candidate.accuracy != current.accuracy ->
            candidate.accuracy < current.accuracy
        else -> candidate.time > current.time
    }
}
