package com.aidley.uvwidget

/**
 * The hourly UV forecast for one location, as fetched from Open-Meteo.
 *
 * Times are UTC epoch seconds (the API is asked for `timeformat=unixtime`, which sidesteps
 * parsing local time strings and daylight-saving edge cases entirely).
 */
data class UvForecast(
    val latitude: Double,
    val longitude: Double,
    val fetchedAtMillis: Long,
    val hourTimesSeconds: LongArray,
    val uvIndexPerHour: DoubleArray
) {
    /**
     * UV index at [epochMillis], linearly interpolated between the surrounding hourly values so
     * the widget moves gradually instead of stepping on the hour. Null when the requested time
     * falls outside the forecast window.
     */
    fun uvIndexAt(epochMillis: Long): Double? {
        val seconds = epochMillis / 1000
        if (hourTimesSeconds.isEmpty()) return null
        if (seconds < hourTimesSeconds.first() || seconds > hourTimesSeconds.last()) return null

        for (i in 0 until hourTimesSeconds.size - 1) {
            val start = hourTimesSeconds[i]
            val end = hourTimesSeconds[i + 1]
            if (seconds in start..end) {
                val startValue = uvIndexPerHour.getOrNull(i) ?: return null
                val endValue = uvIndexPerHour.getOrNull(i + 1) ?: return startValue
                if (end == start) return startValue
                val fraction = (seconds - start).toDouble() / (end - start).toDouble()
                return startValue + (endValue - startValue) * fraction
            }
        }
        return uvIndexPerHour.lastOrNull()
    }

    /** Data older than this is still shown, but the widget will keep trying to refresh it. */
    fun isStale(nowMillis: Long): Boolean = nowMillis - fetchedAtMillis > STALE_AFTER_MILLIS

    /** Beyond this age the forecast is no longer trustworthy and the widget shows "no data". */
    fun isExpired(nowMillis: Long): Boolean = nowMillis - fetchedAtMillis > EXPIRED_AFTER_MILLIS

    // Arrays in a data class need hand-written equals/hashCode to compare by content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UvForecast) return false
        return latitude == other.latitude &&
            longitude == other.longitude &&
            fetchedAtMillis == other.fetchedAtMillis &&
            hourTimesSeconds.contentEquals(other.hourTimesSeconds) &&
            uvIndexPerHour.contentEquals(other.uvIndexPerHour)
    }

    override fun hashCode(): Int {
        var result = latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + fetchedAtMillis.hashCode()
        result = 31 * result + hourTimesSeconds.contentHashCode()
        result = 31 * result + uvIndexPerHour.contentHashCode()
        return result
    }

    companion object {
        const val STALE_AFTER_MILLIS = 90L * 60L * 1000L
        const val EXPIRED_AFTER_MILLIS = 24L * 60L * 60L * 1000L
    }
}

/** Everything the widget needs to draw itself once. */
data class WidgetState(
    val uvIndex: Double?,
    val isSunUp: Boolean,
    val hasLocation: Boolean
)
