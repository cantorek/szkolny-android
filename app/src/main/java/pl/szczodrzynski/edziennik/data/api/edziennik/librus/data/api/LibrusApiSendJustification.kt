/*
 * Copyright (c) Kuba Szczodrzyński 2026-9-9.
 */

package pl.szczodrzynski.edziennik.data.api.edziennik.librus.data.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.greenrobot.eventbus.EventBus
import pl.szczodrzynski.edziennik.data.api.ERROR_LIBRUS_API_JUSTIFICATION_NOT_SENT
import pl.szczodrzynski.edziennik.data.api.LIBRUS_API_3_URL
import pl.szczodrzynski.edziennik.data.api.POST
import pl.szczodrzynski.edziennik.data.api.edziennik.librus.DataLibrus
import pl.szczodrzynski.edziennik.data.api.edziennik.librus.data.LibrusApi
import pl.szczodrzynski.edziennik.data.api.events.ExcuseSentEvent
import pl.szczodrzynski.edziennik.data.api.models.ApiError
import pl.szczodrzynski.edziennik.data.db.entity.LibrusExcuse
import pl.szczodrzynski.edziennik.ext.getLong
import pl.szczodrzynski.edziennik.utils.models.Date

/**
 * Sends an e-Usprawiedliwienie using the Librus API 3.0 (`POST /Justifications`),
 * with the same payload as the official Librus app.
 *
 * An empty [message] is valid - Librus does not require a justification text.
 * An empty [lessons] list means whole days; otherwise [dateFrom] must equal [dateTo].
 */
class LibrusApiSendJustification(
    override val data: DataLibrus,
    private val dateFrom: Date,
    private val dateTo: Date,
    private val lessons: List<Int>,
    private val message: String,
    private val sendNotify: Boolean,
    val onSuccess: () -> Unit,
) : LibrusApi(data, null) {
    companion object {
        const val TAG = "LibrusApiSendJustification"
    }

    init {
        // Librus treats a lesson-scoped excuse as a single day
        val effectiveDateTo = if (lessons.isEmpty()) dateTo else dateFrom

        val payload = JsonObject().apply {
            addProperty("messageFromParent", message)
            add("lessons", JsonArray().apply { lessons.forEach { add(it) } })
            addProperty("dateFrom", dateFrom.stringY_m_d)
            addProperty("dateTo", effectiveDateTo.stringY_m_d)
            addProperty("sendNotify", sendNotify)
        }

        apiGet(TAG, "Justifications", method = POST, payload = payload, baseUrl = LIBRUS_API_3_URL) { json ->
            val id = json.getLong("Id")

            if (id == null) {
                data.error(ApiError(TAG, ERROR_LIBRUS_API_JUSTIFICATION_NOT_SENT).withApiResponse(json))
                EventBus.getDefault().postSticky(ExcuseSentEvent(profileId, null))
                return@apiGet
            }

            // the local placeholder is no longer needed - refresh the real list from Librus
            data.db.librusExcuseDao().clearLocal(profileId)

            val excuse = LibrusExcuse(
                profileId = profileId,
                id = id,
                dateFrom = dateFrom,
                dateTo = effectiveDateTo,
                lessons = lessons.joinToString(","),
                message = message,
                status = LibrusExcuse.Status.SEND,
            )
            data.db.librusExcuseDao().add(excuse)

            LibrusApiJustifications(data, null) {
                EventBus.getDefault().postSticky(ExcuseSentEvent(profileId, excuse))
                onSuccess()
            }
        }
    }
}
