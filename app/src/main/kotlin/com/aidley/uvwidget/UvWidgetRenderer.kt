package com.aidley.uvwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlin.math.roundToInt

/** Builds the widget's RemoteViews and pushes them to the launcher. */
object UvWidgetRenderer {

    /** Shown before the first fetch, or when there is no location or no usable forecast. */
    private const val NO_VALUE = "–"

    fun render(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val state = UvRepository(context).currentState()
        val views = buildViews(context, state)
        appWidgetManager.updateAppWidget(appWidgetIds, views)
    }

    fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, UvWidgetProvider::class.java))
        render(context, manager, ids)
    }

    internal fun buildViews(context: Context, state: WidgetState): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_uv).apply {
            val color = UvColors.forState(state)

            // The background is a white rounded-rect drawable tinted at runtime, which keeps
            // the rounded corners that setBackgroundColor would square off.
            setInt(R.id.uv_background, "setColorFilter", color)
            setTextViewText(R.id.uv_value, formatUvIndex(state.uvIndex))
            setContentDescription(R.id.uv_root, describe(context, state))
            setOnClickPendingIntent(R.id.uv_root, settingsIntent(context))
        }

    /** The UV index is conventionally reported as a whole number. */
    internal fun formatUvIndex(uvIndex: Double?): String = when {
        uvIndex == null || uvIndex.isNaN() -> NO_VALUE
        else -> uvIndex.coerceAtLeast(0.0).roundToInt().toString()
    }

    private fun describe(context: Context, state: WidgetState): String = when {
        !state.hasLocation -> context.getString(R.string.description_no_location)
        state.uvIndex == null -> context.getString(R.string.description_no_data)
        !state.isSunUp -> context.getString(R.string.description_night, formatUvIndex(state.uvIndex))
        else -> context.getString(R.string.description_uv, formatUvIndex(state.uvIndex))
    }

    /**
     * Tapping the widget opens the settings screen.
     *
     * Opening settings also refreshes the cached location fix, which is the one thing the widget
     * cannot do for itself in the background — so this is the more useful tap of the two. The
     * broadcast refresh remains available via [UvWidgetProvider.ACTION_REFRESH].
     */
    private fun settingsIntent(context: Context): PendingIntent {
        val intent = Intent(context, ConfigActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
