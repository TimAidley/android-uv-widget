package com.aidley.uvwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/**
 * The home-screen widget.
 *
 * Drawing is immediate and comes from cached data; anything that needs the network is handed to
 * [UvRefreshWorker], because a broadcast receiver may only run for a few seconds.
 */
class UvWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        UvWidgetRenderer.render(context, appWidgetManager, appWidgetIds)
        UvRefreshWorker.refreshNow(context)
    }

    override fun onEnabled(context: Context) {
        UvRefreshWorker.schedulePeriodicRefresh(context)
    }

    override fun onDisabled(context: Context) {
        UvRefreshWorker.cancelPeriodicRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            // Repaint straight away so the tap feels responsive — the sun may have set since
            // the last update even if the forecast itself has not changed — then go fetch.
            UvWidgetRenderer.renderAll(context)
            UvRefreshWorker.refreshNow(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.aidley.uvwidget.action.REFRESH"
    }
}
