package com.aidley.uvwidget

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The colour ramp is the whole point of the widget, so it gets pinned down here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UvColorsTest {

    @Test
    fun `night wins over any uv value`() {
        assertEquals(UvColors.NIGHT, UvColors.forState(state(uvIndex = 9.0, isSunUp = false)))
        assertEquals(UvColors.NIGHT, UvColors.forState(state(uvIndex = null, isSunUp = false)))
    }

    @Test
    fun `daytime with no data shows the unknown colour`() {
        assertEquals(UvColors.UNKNOWN, UvColors.forState(state(uvIndex = null, isSunUp = true)))
    }

    @Test
    fun `an unconfigured widget is grey, not night blue`() {
        // With no location there is no horizon to test against, so "sun is down" is not a
        // meaningful answer and must not be shown as one.
        val unconfigured = WidgetState(uvIndex = null, isSunUp = false, hasLocation = false)
        assertEquals(UvColors.UNKNOWN, UvColors.forState(unconfigured))
    }

    @Test
    fun `zero uv is green`() {
        val green = UvColors.forUvIndex(0.0)
        assertTrue("expected green to dominate", Color.green(green) > Color.red(green))
    }

    @Test
    fun `extreme uv is red`() {
        val red = UvColors.forUvIndex(12.0)
        assertTrue("expected red to dominate", Color.red(red) > Color.green(red))
        assertEquals(UvColors.forUvIndex(11.0), red)
    }

    @Test
    fun `ramp is continuous between anchors`() {
        // Halfway between green at 0 and yellow at 3 should be a blend of the two.
        val blended = UvColors.forUvIndex(1.5)
        assertNotEquals(UvColors.forUvIndex(0.0), blended)
        assertNotEquals(UvColors.forUvIndex(3.0), blended)
        assertTrue(Color.red(blended) > Color.red(UvColors.forUvIndex(0.0)))
    }

    @Test
    fun `hue moves steadily from green towards red`() {
        // Hue rather than the raw red channel: between deep orange and red the red channel
        // dips slightly even though the colour still reads as more severe.
        var previousHue = Double.MAX_VALUE
        var value = 0.0
        while (value <= 11.0) {
            val hue = hueDegrees(UvColors.forUvIndex(value))
            assertTrue(
                "hue moved back towards green at UV $value ($hue after $previousHue)",
                hue <= previousHue + 0.001
            )
            previousHue = hue
            value += 0.25
        }
        assertTrue("ramp should end on red, ended at hue $previousHue", previousHue < 10.0)
        assertTrue("ramp should start on green", hueDegrees(UvColors.forUvIndex(0.0)) > 100.0)
    }

    @Test
    fun `every colour stays readable behind black text`() {
        val colors = buildList {
            add(UvColors.NIGHT)
            add(UvColors.UNKNOWN)
            var value = 0.0
            while (value <= 13.0) {
                add(UvColors.forUvIndex(value))
                value += 0.5
            }
        }
        for (color in colors) {
            val contrast = (relativeLuminance(color) + 0.05) / 0.05
            assertTrue(
                "contrast %.2f:1 is too low for black text on #%06X".format(contrast, color and 0xFFFFFF),
                contrast >= 4.5
            )
        }
    }

    @Test
    fun `values below zero and NaN degrade gracefully`() {
        assertEquals(UvColors.forUvIndex(0.0), UvColors.forUvIndex(-1.0))
        assertEquals(UvColors.UNKNOWN, UvColors.forUvIndex(Double.NaN))
    }

    private fun state(uvIndex: Double?, isSunUp: Boolean) =
        WidgetState(uvIndex = uvIndex, isSunUp = isSunUp, hasLocation = true)

    /** Hue in degrees, computed here rather than via the framework to keep the test honest. */
    private fun hueDegrees(color: Int): Double {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        val maximum = max(r, max(g, b))
        val minimum = min(r, min(g, b))
        val delta = maximum - minimum
        if (delta == 0.0) return 0.0
        val hue = when (maximum) {
            r -> 60.0 * (((g - b) / delta) % 6.0)
            g -> 60.0 * (((b - r) / delta) + 2.0)
            else -> 60.0 * (((r - g) / delta) + 4.0)
        }
        return if (hue < 0) hue + 360.0 else hue
    }

    /** WCAG relative luminance, used to check the black text stays legible. */
    private fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val v = value / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }
}
