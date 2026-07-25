package com.aidley.uvwidget

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The number on the widget: rounded to a whole UV index, never negative, never blank. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UvWidgetRendererTest {

    @Test
    fun `rounds to the nearest whole uv index`() {
        assertEquals("0", UvWidgetRenderer.formatUvIndex(0.0))
        assertEquals("0", UvWidgetRenderer.formatUvIndex(0.4))
        assertEquals("1", UvWidgetRenderer.formatUvIndex(0.5))
        assertEquals("7", UvWidgetRenderer.formatUvIndex(7.2))
        assertEquals("8", UvWidgetRenderer.formatUvIndex(7.6))
        assertEquals("12", UvWidgetRenderer.formatUvIndex(11.7))
    }

    @Test
    fun `missing or nonsensical values show a dash`() {
        assertEquals("–", UvWidgetRenderer.formatUvIndex(null))
        assertEquals("–", UvWidgetRenderer.formatUvIndex(Double.NaN))
    }

    @Test
    fun `negative values from the api are clamped to zero`() {
        assertEquals("0", UvWidgetRenderer.formatUvIndex(-0.3))
    }
}
