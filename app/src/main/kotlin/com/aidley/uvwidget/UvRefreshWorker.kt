package com.aidley.uvwidget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Refreshes the cached forecast off the main thread and repaints the widget.
 *
 * WorkManager rather than the widget's own `updatePeriodMillis`, which wakes the device and
 * cannot be triggered on demand. The periodic job also keeps the colour honest across sunrise
 * and sunset even when the forecast data itself is unchanged.
 */
class UvRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        withContext(Dispatchers.IO) {
            UvRepository(applicationContext).refresh()
        }
        UvWidgetRenderer.renderAll(applicationContext)
        // The widget always has something to draw, so a failed fetch is not a failed job —
        // retrying aggressively would drain the battery for a number that changes hourly.
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "uv-widget-periodic-refresh"
        private const val ONE_SHOT_WORK_NAME = "uv-widget-refresh-now"

        /**
         * Half-hourly. The forecast is hourly, but the widget interpolates between hours and
         * needs to notice sunrise and sunset reasonably promptly.
         */
        private const val REFRESH_INTERVAL_MINUTES = 30L

        fun schedulePeriodicRefresh(context: Context) {
            val request = PeriodicWorkRequestBuilder<UvRefreshWorker>(
                REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).setConstraints(networkConstraints()).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancelPeriodicRefresh(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }

        /** Immediate refresh, used when a widget is added, tapped, or reconfigured. */
        fun refreshNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UvRefreshWorker>()
                .setConstraints(networkConstraints())
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        private fun networkConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
