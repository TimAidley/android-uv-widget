package com.aidley.uvwidget

import android.graphics.Color

/**
 * Background colours for the widget.
 *
 * Every colour here is light enough to carry black text: the widget draws the UV number in
 * black, so a background that is too dark would make it unreadable. The darkest colour in the
 * ramp (extreme red) still gives roughly 5:1 contrast against black.
 */
object UvColors {

    /** Shown whenever the sun is below the horizon, regardless of the forecast value. */
    const val NIGHT = 0xFF2196F3.toInt()

    /** Shown before the first successful fetch, or when the location/network is unavailable. */
    const val UNKNOWN = 0xFFBDBDBD.toInt()

    /**
     * Anchor points of the daytime ramp, low to high. Colours are interpolated between anchors
     * so the widget shifts smoothly through the day rather than jumping between WHO bands.
     */
    private val RAMP = arrayOf(
        0.0 to 0xFF4CAF50.toInt(), // low: green
        3.0 to 0xFFFFEB3B.toInt(), // moderate: yellow
        6.0 to 0xFFFF9800.toInt(), // high: orange
        8.0 to 0xFFFF5722.toInt(), // very high: deep orange
        11.0 to 0xFFE53935.toInt() // extreme: red
    )

    /** Background colour for a UV index, clamped to the ends of the ramp. */
    fun forUvIndex(uvIndex: Double): Int {
        if (uvIndex.isNaN()) return UNKNOWN
        if (uvIndex <= RAMP.first().first) return RAMP.first().second
        if (uvIndex >= RAMP.last().first) return RAMP.last().second

        for (i in 0 until RAMP.size - 1) {
            val (lowValue, lowColor) = RAMP[i]
            val (highValue, highColor) = RAMP[i + 1]
            if (uvIndex <= highValue) {
                val fraction = (uvIndex - lowValue) / (highValue - lowValue)
                return blend(lowColor, highColor, fraction)
            }
        }
        return RAMP.last().second
    }

    /**
     * Background colour for what the widget currently knows.
     *
     * Without a location there is no horizon to compare against, so that case has to be caught
     * before the night check — otherwise an unconfigured widget would sit there showing the
     * night colour as though it were reporting something.
     */
    fun forState(state: WidgetState): Int = when {
        !state.hasLocation -> UNKNOWN
        !state.isSunUp -> NIGHT
        state.uvIndex == null -> UNKNOWN
        else -> forUvIndex(state.uvIndex)
    }

    private fun blend(from: Int, to: Int, fraction: Double): Int {
        val f = fraction.coerceIn(0.0, 1.0)
        fun channel(extract: (Int) -> Int) =
            (extract(from) + (extract(to) - extract(from)) * f).toInt().coerceIn(0, 255)
        return Color.argb(
            255,
            channel { Color.red(it) },
            channel { Color.green(it) },
            channel { Color.blue(it) }
        )
    }
}
