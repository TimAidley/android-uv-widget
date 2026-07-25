package com.aidley.uvwidget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent state for the widget: the location to forecast for, and the last forecast fetched.
 *
 * SharedPreferences rather than DataStore because everything here is read synchronously from a
 * broadcast receiver, where a blocking read of a handful of values is the simpler correct thing.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** True when the widget should follow the device's location instead of a fixed point. */
    var useDeviceLocation: Boolean
        get() = prefs.getBoolean(KEY_USE_DEVICE_LOCATION, true)
        set(value) = prefs.edit { putBoolean(KEY_USE_DEVICE_LOCATION, value) }

    /** Location entered by hand in the config screen; used when device location is off. */
    var manualLocation: Coordinates?
        get() = readCoordinates(KEY_MANUAL_LAT, KEY_MANUAL_LON)
        set(value) = writeCoordinates(KEY_MANUAL_LAT, KEY_MANUAL_LON, value)

    /**
     * Last location the device reported while the app was in the foreground.
     *
     * Background location access is restricted from Android 10 onwards, so the widget's
     * background refresh reuses this cached fix rather than asking for a new one.
     */
    var cachedDeviceLocation: Coordinates?
        get() = readCoordinates(KEY_CACHED_LAT, KEY_CACHED_LON)
        set(value) = writeCoordinates(KEY_CACHED_LAT, KEY_CACHED_LON, value)

    /** The location the widget should forecast for right now, or null if it has none yet. */
    val effectiveLocation: Coordinates?
        get() = if (useDeviceLocation) cachedDeviceLocation ?: manualLocation else manualLocation

    var forecast: UvForecast?
        get() {
            val raw = prefs.getString(KEY_FORECAST, null) ?: return null
            return runCatching { decodeForecast(JSONObject(raw)) }.getOrNull()
        }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_FORECAST) else putString(KEY_FORECAST, encodeForecast(value).toString())
        }

    private fun readCoordinates(latKey: String, lonKey: String): Coordinates? {
        if (!prefs.contains(latKey) || !prefs.contains(lonKey)) return null
        val lat = prefs.getFloat(latKey, Float.NaN).toDouble()
        val lon = prefs.getFloat(lonKey, Float.NaN).toDouble()
        return if (lat.isNaN() || lon.isNaN()) null else Coordinates(lat, lon)
    }

    private fun writeCoordinates(latKey: String, lonKey: String, value: Coordinates?) {
        prefs.edit {
            if (value == null) {
                remove(latKey)
                remove(lonKey)
            } else {
                putFloat(latKey, value.latitude.toFloat())
                putFloat(lonKey, value.longitude.toFloat())
            }
        }
    }

    private fun encodeForecast(forecast: UvForecast) = JSONObject().apply {
        put(FIELD_LAT, forecast.latitude)
        put(FIELD_LON, forecast.longitude)
        put(FIELD_FETCHED_AT, forecast.fetchedAtMillis)
        put(FIELD_TIMES, JSONArray().apply { forecast.hourTimesSeconds.forEach { put(it) } })
        put(FIELD_VALUES, JSONArray().apply { forecast.uvIndexPerHour.forEach { put(it) } })
    }

    private fun decodeForecast(json: JSONObject): UvForecast {
        val times = json.getJSONArray(FIELD_TIMES)
        val values = json.getJSONArray(FIELD_VALUES)
        return UvForecast(
            latitude = json.getDouble(FIELD_LAT),
            longitude = json.getDouble(FIELD_LON),
            fetchedAtMillis = json.getLong(FIELD_FETCHED_AT),
            hourTimesSeconds = LongArray(times.length()) { times.getLong(it) },
            uvIndexPerHour = DoubleArray(values.length()) { values.getDouble(it) }
        )
    }

    private companion object {
        const val NAME = "uv_widget"

        const val KEY_USE_DEVICE_LOCATION = "use_device_location"
        const val KEY_MANUAL_LAT = "manual_lat"
        const val KEY_MANUAL_LON = "manual_lon"
        const val KEY_CACHED_LAT = "cached_lat"
        const val KEY_CACHED_LON = "cached_lon"
        const val KEY_FORECAST = "forecast"

        const val FIELD_LAT = "lat"
        const val FIELD_LON = "lon"
        const val FIELD_FETCHED_AT = "fetched_at"
        const val FIELD_TIMES = "times"
        const val FIELD_VALUES = "values"
    }
}

/** A point on the globe, in degrees. */
data class Coordinates(val latitude: Double, val longitude: Double) {
    val isValid: Boolean
        get() = latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !latitude.isNaN() && !longitude.isNaN()
}
