package pl.szczodrzynski.edziennik.sync

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import pl.szczodrzynski.edziennik.App
import pl.szczodrzynski.edziennik.data.api.edziennik.EdziennikTask
import pl.szczodrzynski.edziennik.data.api.events.UserActionRequiredEvent
import pl.szczodrzynski.edziennik.data.api.interfaces.EdziennikCallback
import pl.szczodrzynski.edziennik.data.api.models.ApiError
import pl.szczodrzynski.edziennik.data.api.task.SzkolnyTask
import pl.szczodrzynski.edziennik.data.db.entity.Profile
import pl.szczodrzynski.edziennik.utils.Utils.d
import java.util.concurrent.TimeUnit

/**
 * Periodic background synchronization.
 *
 * The sync is run *inside* the worker, instead of delegating it to ApiService.
 * Starting a foreground service from the background is not allowed since Android 12,
 * and running a WorkManager job is not one of the exemptions - the service start
 * would throw, aborting the sync entirely. ApiService is still used for
 * user-triggered syncs, where the app is in the foreground.
 */
class SyncWorker(val context: Context, val params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val TAG = "SyncWorker"

        /** A single task (one profile) may not take longer than this. */
        private const val TASK_TIMEOUT = 10 * 60 * 1000L

        /** WorkManager rejects a flex interval shorter than 5 minutes. */
        private const val MIN_FLEX = 5L * 60
        private const val MAX_FLEX = 30L * 60

        /**
         * Schedule the periodic sync job, keeping the existing schedule if there is one.
         */
        fun scheduleNext(app: App) = enqueue(app, ExistingPeriodicWorkPolicy.KEEP)

        /**
         * Schedule the periodic sync job, applying the current [ConfigSync] settings.
         *
         * If [ConfigSync.enabled] is not true, just cancel every job.
         */
        fun rescheduleNext(app: App) = enqueue(app, ExistingPeriodicWorkPolicy.REPLACE)

        private fun enqueue(app: App, policy: ExistingPeriodicWorkPolicy) {
            if (!app.config.sync.enabled) {
                cancelNext(app)
                return
            }

            // WorkManager enforces a 15-minute minimum; the UI offers 30 minutes upwards
            val syncInterval = app.config.sync.interval.toLong()
            // Run within a flex window at the end of each period instead of at a fixed
            // point, so syncs are not pinned to the same wall-clock minute every time
            // and the system can batch them with other work.
            val flexInterval = (syncInterval / 4).coerceIn(MIN_FLEX, MAX_FLEX)
            d(TAG, "Scheduling periodic work every $syncInterval s, flex $flexInterval s (policy = $policy)")

            val constraints = Constraints.Builder()
                    .setRequiredNetworkType(
                            if (app.config.sync.onlyWifi)
                                NetworkType.UNMETERED
                            else
                                NetworkType.CONNECTED)
                    .build()

            val syncWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                    syncInterval, TimeUnit.SECONDS,
                    flexInterval, TimeUnit.SECONDS)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                    .addTag(TAG)
                    .build()

            WorkManager.getInstance(app).enqueueUniquePeriodicWork(TAG, policy, syncWorkRequest)
        }

        /**
         * Cancel any scheduled sync job.
         */
        fun cancelNext(app: App) {
            d(TAG, "Cancelling work by tag $TAG")
            // by tag, so that the legacy chain of one-time requests is cancelled as well
            WorkManager.getInstance(app).cancelAllWorkByTag(TAG)
        }
    }

    private val app by lazy { applicationContext as App }

    override suspend fun doWork(): Result {
        d(TAG, "Running worker ID ${params.id}")
        if (!app.config.sync.enabled) {
            cancelNext(app)
            return Result.success()
        }

        return try {
            syncAllProfiles()
            Result.success()
        } catch (e: Exception) {
            d(TAG, "Background sync failed: $e")
            Result.retry()
        }
    }

    /**
     * Run a sync task for every profile, then create and post the notifications.
     *
     * This mirrors ApiService's task queue, without the progress notification
     * and the EventBus updates - nobody is watching them in the background.
     */
    private suspend fun syncAllProfiles() {
        val syncingProfiles = mutableListOf<Profile>()

        for (profileId in app.db.profileDao().idsForSyncNow) {
            val task = EdziennikTask.syncProfile(profileId)
            task.prepare(app)
            task.profile?.let { syncingProfiles += it }
            d(TAG, "Syncing profile $profileId")
            awaitTask({ callback -> task.run(app, callback) }, onTimeout = { task.cancel() })
        }

        val szkolnyTask = SzkolnyTask(app, syncingProfiles)
        szkolnyTask.prepare(app)
        awaitTask({ callback -> szkolnyTask.run(callback) })
    }

    /**
     * Start a task and suspend until its callback reports completion,
     * a critical error, or [TASK_TIMEOUT] passes.
     */
    private suspend fun awaitTask(block: (EdziennikCallback) -> Unit, onTimeout: () -> Unit = {}) {
        val finished = CompletableDeferred<Unit>()

        val callback = object : EdziennikCallback {
            override fun onCompleted() {
                finished.complete(Unit)
            }

            override fun onRequiresUserAction(event: UserActionRequiredEvent) {
                app.userActionManager.sendToUser(event)
                finished.complete(Unit)
            }

            override fun onError(apiError: ApiError) {
                d(TAG, "Sync error: $apiError")
                // non-critical errors do not stop the task
                if (apiError.isCritical)
                    finished.complete(Unit)
            }

            override fun onProgress(step: Float) {}
            override fun onStartProgress(stringRes: Int) {}
        }

        try {
            block(callback)
        } catch (e: Exception) {
            d(TAG, "Task threw an exception: $e")
            return
        }

        if (withTimeoutOrNull(TASK_TIMEOUT) { finished.await() } == null) {
            d(TAG, "Task timed out after $TASK_TIMEOUT ms")
            onTimeout()
        }
    }
}
