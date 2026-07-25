package com.aidley.uvwidget

import android.content.Context
import kotlin.math.abs

/**
 * Decides what the widget should display, and refreshes the cached forecast when needed.
 *
 * Keeping this logic out of the widget provider means the state shown on screen is derived in
 * one place, whether the trigger was a scheduled refresh, a tap, or the widget being added.
 */
class UvRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)

    /** What to draw right now, from cached data only. Safe to call on the main thread. */
    fun currentState(nowMillis: Long = System.currentTimeMillis()): WidgetState {
        val location = prefs.effectiveLocation
            ?: return WidgetState(uvIndex = null, isSunUp = false, hasLocation = false)

        val sunUp = SolarPosition.isSunUp(nowMillis, location.latitude, location.longitude)

        // A forecast for somewhere the user no longer is would be worse than showing nothing.
        val forecast = prefs.forecast
            ?.takeUnless { it.isExpired(nowMillis) }
            ?.takeIf { it.covers(location) }
        val uvIndex = forecast?.uvIndexAt(nowMillis)

        return WidgetState(uvIndex = uvIndex, isSunUp = sunUp, hasLocation = true)
    }

    /**
     * Refreshes the cached forecast if it is missing, stale, or for a different place.
     *
     * Blocking network call — run this from a worker, never from the main thread. Returns the
     * state to display afterwards, which falls back to cached data when the fetch fails.
     */
    fun refresh(nowMillis: Long = System.currentTimeMillis()): WidgetState {
        refreshDeviceLocationIfPossible()

        val location = prefs.effectiveLocation ?: return currentState(nowMillis)
        val cached = prefs.forecast

        if (cached != null && !cached.isStale(nowMillis) && cached.covers(location)) {
            return currentState(nowMillis)
        }

        OpenMeteoClient.fetchUvForecast(location, nowMillis)?.let { prefs.forecast = it }
        return currentState(nowMillis)
    }

    /**
     * Opportunistically updates the cached device location.
     *
     * This usually only succeeds in the foreground — background location access is restricted
     * from Android 10 — so a null result just leaves the previously cached fix in place.
     */
    private fun refreshDeviceLocationIfPossible() {
        if (!prefs.useDeviceLocation) return
        LocationSource.lastKnownLocation(appContext)?.let { prefs.cachedDeviceLocation = it }
    }

    /** A cached forecast is reusable while the location has not moved appreciably. */
    private fun UvForecast.covers(location: Coordinates): Boolean =
        abs(latitude - location.latitude) < LOCATION_TOLERANCE_DEGREES &&
            abs(longitude - location.longitude) < LOCATION_TOLERANCE_DEGREES

    private companion object {
        /** Roughly 5 km of latitude; UV varies far more slowly than that. */
        const val LOCATION_TOLERANCE_DEGREES = 0.05
    }
}
