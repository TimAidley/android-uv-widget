package com.aidley.uvwidget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.text.format.DateFormat
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import java.util.Date
import kotlin.math.ceil
import kotlin.math.max

/**
 * The UV index across twelve hours, as a filled curve with a line marking now.
 *
 * Drawing samples the curve once per pixel column rather than joining the hourly points, so the
 * shape is the interpolated one the widget itself reports, not a polyline through the hours.
 */
class UvGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var curve: MonotoneCurve? = null
    private var location: Coordinates? = null
    private var windowStartMillis = 0L
    private var windowEndMillis = 0L
    private var ceiling = MINIMUM_CEILING

    /** X of the user's finger while it is down, or null when it is not. */
    private var touchX: Float? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1f) }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(1.5f) }
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(12f) }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(14f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val touchPath = Path()

    init {
        val primary = themeColor(android.R.attr.textColorPrimary, Color.DKGRAY)
        val secondary = themeColor(android.R.attr.textColorSecondary, Color.GRAY)
        curvePaint.color = primary
        nowPaint.color = primary
        valuePaint.color = primary
        touchPaint.color = secondary
        labelPaint.color = secondary
        axisPaint.color = withAlpha(secondary, AXIS_ALPHA)
    }

    /**
     * Supplies the data to draw. Pass a null [forecast] or [location] to draw nothing — the
     * caller is expected to hide the view in that case.
     */
    fun setForecast(
        forecast: UvForecast?,
        location: Coordinates?,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        this.location = location
        curve = forecast?.let { MonotoneCurve.from(it) }

        val place = location
        val drawable = curve
        if (drawable != null && place != null) {
            windowStartMillis = UvGraphWindow.chooseStart(nowMillis) { at ->
                SolarPosition.isSunUp(at, place.latitude, place.longitude)
            }
            windowEndMillis = windowStartMillis + UvGraphWindow.SPAN_MILLIS
            val peak = drawable.peakBetween(
                windowStartMillis / 1000.0,
                windowEndMillis / 1000.0
            )
            ceiling = max(MINIMUM_CEILING, ceil(peak))
        }

        contentDescription = describeForAccessibility(nowMillis)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val drawable = curve ?: return

        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat() + valuePaint.textSize * 1.4f // room for the value labels
        val bottom = (height - paddingBottom).toFloat() - labelPaint.textSize * 1.8f
        if (right - left < dp(24f) || bottom - top < dp(24f)) return

        drawCurveAndFill(canvas, drawable, left, right, top, bottom)
        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        drawHourLabels(canvas, left, right, bottom)

        val nowMillis = System.currentTimeMillis()
        if (nowMillis in windowStartMillis..windowEndMillis) {
            val x = xFor(nowMillis, left, right)
            canvas.drawLine(x, top, x, bottom, nowPaint)
            drawValueLabel(canvas, drawable, nowMillis, x, left, right, top)
        }

        touchX?.let { finger ->
            val x = finger.coerceIn(left, right)
            touchPath.reset()
            touchPath.moveTo(x, top)
            touchPath.lineTo(x, bottom)
            canvas.drawPath(touchPath, touchPaint)
            drawValueLabel(canvas, drawable, millisFor(x, left, right), x, left, right, top)
        }
    }

    private fun drawCurveAndFill(
        canvas: Canvas,
        drawable: MonotoneCurve,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float
    ) {
        val outline = Path()
        val filled = Path()
        var started = false
        var firstX = left
        var lastX = left

        var x = left
        while (x <= right) {
            val value = drawable.valueAt(millisFor(x, left, right) / 1000.0)
            if (value == null) {
                x += SAMPLE_STEP_PX
                continue
            }
            val y = bottom - (value / ceiling).toFloat() * (bottom - top)
            if (!started) {
                outline.moveTo(x, y)
                filled.moveTo(x, y)
                firstX = x
                started = true
            } else {
                outline.lineTo(x, y)
                filled.lineTo(x, y)
            }
            lastX = x
            x += SAMPLE_STEP_PX
        }
        if (!started) return

        // Close the fill down onto the axis: the colour belongs under the line, never above it.
        filled.lineTo(lastX, bottom)
        filled.lineTo(firstX, bottom)
        filled.close()

        fillPaint.shader = rampShader(drawable, left, right)
        canvas.drawPath(filled, fillPaint)
        fillPaint.shader = null
        canvas.drawPath(outline, curvePaint)
    }

    /**
     * A horizontal gradient of the widget's own colours, sampled from the curve.
     *
     * Colouring by the value at each moment — rather than by height on the axis — keeps the graph
     * saying the same thing the widget does: this is what UV 7 looks like.
     */
    private fun rampShader(drawable: MonotoneCurve, left: Float, right: Float): Shader {
        val colors = IntArray(GRADIENT_STOPS)
        for (i in 0 until GRADIENT_STOPS) {
            val x = left + (right - left) * i / (GRADIENT_STOPS - 1)
            val value = drawable.valueAt(millisFor(x, left, right) / 1000.0) ?: 0.0
            colors[i] = withAlpha(UvColors.forUvIndex(value), FILL_ALPHA)
        }
        return LinearGradient(left, 0f, right, 0f, colors, null, Shader.TileMode.CLAMP)
    }

    private fun drawHourLabels(canvas: Canvas, left: Float, right: Float, bottom: Float) {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH" else "h a"
        val format = DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), pattern)
        val formatter = java.text.SimpleDateFormat(format, java.util.Locale.getDefault())

        val calendar = Calendar.getInstance().apply {
            timeInMillis = windowStartMillis
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Step in whole hours from a clock boundary, so labels read 09:00 rather than 09:37.
        while (calendar.get(Calendar.HOUR_OF_DAY) % LABEL_EVERY_HOURS != 0 ||
            calendar.timeInMillis < windowStartMillis
        ) {
            calendar.add(Calendar.HOUR_OF_DAY, 1)
        }

        val baseline = bottom + labelPaint.textSize * 1.4f
        while (calendar.timeInMillis <= windowEndMillis) {
            val x = xFor(calendar.timeInMillis, left, right)
            val text = formatter.format(Date(calendar.timeInMillis))
            val halfWidth = labelPaint.measureText(text) / 2f
            // Nudge end labels inwards rather than letting them run off the edge.
            val textX = (x - halfWidth).coerceIn(left, right - halfWidth * 2f)
            canvas.drawText(text, textX, baseline, labelPaint)
            canvas.drawLine(x, bottom, x, bottom + dp(3f), axisPaint)
            calendar.add(Calendar.HOUR_OF_DAY, LABEL_EVERY_HOURS)
        }
    }

    private fun drawValueLabel(
        canvas: Canvas,
        drawable: MonotoneCurve,
        atMillis: Long,
        x: Float,
        left: Float,
        right: Float,
        top: Float
    ) {
        val value = drawable.valueAt(atMillis / 1000.0) ?: return
        val text = UvWidgetRenderer.formatUvIndex(value)
        val textWidth = valuePaint.measureText(text)
        val gap = dp(6f)
        // Sit the label to the right of its line, flipping to the left near the far edge.
        val textX = if (x + gap + textWidth <= right) x + gap else x - gap - textWidth
        canvas.drawText(text, textX.coerceAtLeast(left), top - dp(6f), valuePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (curve == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // The graph sits inside a ScrollView, which would otherwise take over the drag
                // the moment it wandered off the horizontal.
                parent?.requestDisallowInterceptTouchEvent(true)
                touchX = event.x
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                touchX = null
                invalidate()
            }
            else -> return false
        }
        return true
    }

    private fun xFor(atMillis: Long, left: Float, right: Float): Float {
        val fraction = (atMillis - windowStartMillis).toDouble() /
            (windowEndMillis - windowStartMillis).toDouble()
        return left + (right - left) * fraction.toFloat()
    }

    private fun millisFor(x: Float, left: Float, right: Float): Long {
        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return windowStartMillis +
            ((windowEndMillis - windowStartMillis) * fraction.toDouble()).toLong()
    }

    private fun describeForAccessibility(nowMillis: Long): String? {
        val value = curve?.valueAt(nowMillis / 1000.0) ?: return null
        return context.getString(R.string.graph_description, UvWidgetRenderer.formatUvIndex(value))
    }

    private fun themeColor(attr: Int, fallback: Int): Int {
        val typed = TypedValue()
        return if (context.theme.resolveAttribute(attr, typed, true)) {
            if (typed.resourceId != 0) context.getColor(typed.resourceId) else typed.data
        } else {
            fallback
        }
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )

    private fun sp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics
    )

    private companion object {
        /** Auto-fitting the axis to a winter peak of 1 would magnify noise into a mountain. */
        const val MINIMUM_CEILING = 4.0

        const val FILL_ALPHA = 90
        const val AXIS_ALPHA = 120
        const val GRADIENT_STOPS = 64
        const val LABEL_EVERY_HOURS = 3
        const val SAMPLE_STEP_PX = 2f
    }
}
