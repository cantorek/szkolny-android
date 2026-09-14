/*
 * Copyright (c) Kuba Szczodrzyński 2022-10-22.
 */

package pl.szczodrzynski.edziennik.utils.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.greenrobot.eventbus.EventBus
import pl.szczodrzynski.edziennik.App
import pl.szczodrzynski.edziennik.BuildConfig
import pl.szczodrzynski.edziennik.R
import pl.szczodrzynski.edziennik.data.api.szkolny.response.Update
import pl.szczodrzynski.edziennik.data.api.task.PostNotifications
import pl.szczodrzynski.edziennik.data.db.entity.Notification
import pl.szczodrzynski.edziennik.data.db.enums.NotificationType
import pl.szczodrzynski.edziennik.ext.concat
import pl.szczodrzynski.edziennik.ext.resolveString
import pl.szczodrzynski.edziennik.utils.html.BetterHtml
import kotlin.coroutines.CoroutineContext

class UpdateManager(val app: App) : CoroutineScope {
    companion object {
        private const val TAG = "UpdateManager"

        /**
         * Own update feed - a JSON matching [Update], published next to the APK.
         * The szkolny.eu channel is not used: it serves officially signed builds,
         * which cannot be installed over this one.
         */
        const val UPDATE_URL = "https://dom.wycislak.pl/plikos/update.json"
    }

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Default

    /**
     * Check for updates on the specified [maxChannel].
     * If the running build is of "more-unstable" type,
     * that channel is used instead.
     *
     * Optionally, post a notification if [notify] is true.
     *
     * @return [Result] containing a newer update, or null if not available
     */
    suspend fun checkNow(
        maxChannel: Update.Type,
        notify: Boolean,
    ): Result<Update?> = withContext(Dispatchers.IO) {
        return@withContext checkNowSync(maxChannel, notify)
    }

    /**
     * Check for updates on the specified [maxChannel].
     * If the running build is of "more-unstable" type,
     * that channel is used instead.
     *
     * Optionally, post a notification if [notify] is true.
     *
     * @return [Result] containing a newer update, or null if not available
     */
    fun checkNowSync(
        @Suppress("UNUSED_PARAMETER") maxChannel: Update.Type,
        notify: Boolean,
    ): Result<Update?> {
        // the feed holds a single build - there are no release channels here
        val update = try {
            fetchUpdate()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return Result.success(process(update, notify))
    }

    private fun fetchUpdate(): Update? {
        val request = Request.Builder()
            .url(UPDATE_URL)
            .build()

        app.http.newCall(request).execute().use { response ->
            if (response.code() != 200)
                return null
            val body = response.body()?.string() ?: return null
            return app.gson.fromJson(body, Update::class.java)
        }
    }

    /**
     * Process the update: check if the version is newer, and optionally
     * post a notification.
     *
     * @return [update] if it's a newer version, null otherwise
     */
    fun process(update: Update?, notify: Boolean): Update? {
        if (update == null || update.versionCode <= BuildConfig.VERSION_CODE) {
            app.config.update = null
            return null
        }
        app.config.update = update

        if (EventBus.getDefault().hasSubscriberForEvent(update::class.java)) {
            EventBus.getDefault().postSticky(update)
            return update
        }

        if (notify)
            notify(update)
        return update
    }

    fun notify(update: Update) {
        if (!app.config.sync.notifyAboutUpdates)
            return
        val bigText = listOf(
            app.getString(R.string.notification_updates_text, update.versionName),
            update.releaseNotes?.let { BetterHtml.fromHtml(context = null, it) },
        )
        val notification = Notification(
            id = System.currentTimeMillis(),
            title = R.string.notification_updates_title.resolveString(app),
            text = bigText.concat("\n").toString(),
            type = NotificationType.UPDATE,
            profileId = null,
            profileName = R.string.notification_updates_title.resolveString(app),
        ).addExtra("action", "updateRequest")
        app.db.notificationDao().add(notification)
        PostNotifications(app, listOf(notification))
    }
}
