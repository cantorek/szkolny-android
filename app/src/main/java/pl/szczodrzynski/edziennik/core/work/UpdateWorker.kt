/*
 * Copyright (c) Kuba Szczodrzyński 2020-1-18.
 */

package pl.szczodrzynski.edziennik.core.work

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import pl.szczodrzynski.edziennik.*
import pl.szczodrzynski.edziennik.data.api.szkolny.response.Update
import pl.szczodrzynski.edziennik.ext.DAY
import pl.szczodrzynski.edziennik.ext.HOUR
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class UpdateWorker(val context: Context, val params: WorkerParameters) : Worker(context, params), CoroutineScope {
    companion object {
        const val TAG = "UpdateWorker"

        /**
         * Schedule the periodic update check, keeping the existing schedule if there is one.
         */
        fun scheduleNext(app: App) = enqueue(app, ExistingPeriodicWorkPolicy.KEEP)

        /**
         * Schedule the periodic update check, applying the current settings
         * to a job that is already scheduled.
         *
         * If [ConfigSync.notifyAboutUpdates] is not true, just cancel every job.
         */
        fun rescheduleNext(app: App) = enqueue(app, ExistingPeriodicWorkPolicy.UPDATE)

        private fun enqueue(app: App, policy: ExistingPeriodicWorkPolicy) {
            if (!app.config.sync.notifyAboutUpdates) {
                cancelNext(app)
                return
            }
            val syncInterval =
                if (app.buildManager.releaseType == Update.Type.NIGHTLY)
                    12 * HOUR
                else
                    4 * DAY

            Timber.d("Scheduling periodic work every $syncInterval seconds (policy = $policy)")

            val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val syncWorkRequest = PeriodicWorkRequestBuilder<UpdateWorker>(syncInterval, TimeUnit.SECONDS)
                    .setConstraints(constraints)
                    .addTag(TAG)
                    .build()

            WorkManager.getInstance(app).enqueueUniquePeriodicWork(TAG, policy, syncWorkRequest)
        }

        /**
         * Cancel any scheduled update check job.
         */
        fun cancelNext(app: App) {
            Timber.d("Cancelling work by tag $TAG")
            // by tag, so that the legacy chain of one-time requests is cancelled as well
            WorkManager.getInstance(app).cancelAllWorkByTag(TAG)
        }
    }

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    override fun doWork(): Result {
        Timber.d("Running worker ID ${params.id}")
        val app = context as App
        if (!app.config.sync.notifyAboutUpdates) {
            return Result.success()
        }

        val channel = if (App.devMode)
            Update.Type.BETA
        else
            Update.Type.RELEASE
        app.updateManager.checkNowSync(channel, notify = true)

        return Result.success()
    }
}
