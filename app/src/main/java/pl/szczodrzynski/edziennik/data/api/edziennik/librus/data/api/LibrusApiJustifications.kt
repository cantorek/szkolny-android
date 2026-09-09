/*
 * Copyright (c) Kuba Szczodrzyński 2026-9-9.
 */

package pl.szczodrzynski.edziennik.data.api.edziennik.librus.data.api

import pl.szczodrzynski.edziennik.data.api.ERROR_LIBRUS_API_ACCESS_DENIED
import pl.szczodrzynski.edziennik.data.api.ERROR_LIBRUS_API_DATA_NOT_FOUND
import pl.szczodrzynski.edziennik.data.api.ERROR_LIBRUS_API_INCORRECT_ENDPOINT
import pl.szczodrzynski.edziennik.data.api.ERROR_LIBRUS_API_OTHER
import pl.szczodrzynski.edziennik.data.api.ERROR_LIBRUS_API_RESOURCE_ACCESS_DENIED
import pl.szczodrzynski.edziennik.data.api.ERROR_LIBRUS_API_RESOURCE_NOT_FOUND
import pl.szczodrzynski.edziennik.data.api.LIBRUS_API_3_URL
import pl.szczodrzynski.edziennik.data.api.edziennik.librus.DataLibrus
import pl.szczodrzynski.edziennik.data.api.edziennik.librus.ENDPOINT_LIBRUS_API_JUSTIFICATIONS
import pl.szczodrzynski.edziennik.data.api.edziennik.librus.data.LibrusApi
import pl.szczodrzynski.edziennik.data.db.entity.LibrusExcuse
import pl.szczodrzynski.edziennik.ext.*
import pl.szczodrzynski.edziennik.utils.models.Date

/**
 * Downloads the list of e-Usprawiedliwienia (absence excuses) for a parent account.
 *
 * Uses the Librus API 3.0, same as the official app:
 * `GET /Justifications?dateFrom=...&dateTo=...`
 *
 * A 403 (or any response without a `data` array) means the school does not have the
 * e-Usprawiedliwienia module enabled - this is not an error, the feature just gets disabled.
 */
class LibrusApiJustifications(
    override val data: DataLibrus,
    override val lastSync: Long?,
    val onSuccess: (endpointId: Int) -> Unit,
) : LibrusApi(data, lastSync) {
    companion object {
        const val TAG = "LibrusApiJustifications"
    }

    init {
        val profile = data.profile
        if (profile == null) {
            onSuccess(ENDPOINT_LIBRUS_API_JUSTIFICATIONS)
        } else {
            val dateFrom = profile.dateSemester1Start.stringY_m_d
            val dateTo = profile.dateYearEnd.stringY_m_d

            apiGet(
                TAG,
                "Justifications?dateFrom=$dateFrom&dateTo=$dateTo",
                baseUrl = LIBRUS_API_3_URL,
                // the module is optional per school - a denial is a valid outcome, not an error.
                // token/scope errors are deliberately NOT ignored, so re-login still works.
                ignoreErrors = listOf(
                    ERROR_LIBRUS_API_ACCESS_DENIED,
                    ERROR_LIBRUS_API_RESOURCE_ACCESS_DENIED,
                    ERROR_LIBRUS_API_RESOURCE_NOT_FOUND,
                    ERROR_LIBRUS_API_DATA_NOT_FOUND,
                    ERROR_LIBRUS_API_INCORRECT_ENDPOINT,
                    ERROR_LIBRUS_API_OTHER,
                ),
            ) { json ->
                val justifications = json.getJsonArray("data")

                if (justifications == null) {
                    // module not available for this account
                    data.app.config[profileId].librusExcusesUnavailable = true
                    data.setSyncNext(ENDPOINT_LIBRUS_API_JUSTIFICATIONS, 1 * WEEK)
                    onSuccess(ENDPOINT_LIBRUS_API_JUSTIFICATIONS)
                    return@apiGet
                }

                val excuseList = justifications.asJsonObjectList().mapNotNull { it.toExcuse() }

                data.db.librusExcuseDao().clearRemote(profileId)
                data.db.librusExcuseDao().addAll(excuseList)

                data.app.config[profileId].librusExcusesUnavailable = false
                data.setSyncNext(ENDPOINT_LIBRUS_API_JUSTIFICATIONS, 2 * HOUR)
                onSuccess(ENDPOINT_LIBRUS_API_JUSTIFICATIONS)
            }
        }
    }

    private fun com.google.gson.JsonObject.toExcuse(): LibrusExcuse? {
        val id = getLong("id") ?: return null
        val dateFrom = getString("dateFrom")?.let { Date.fromY_m_d(it) } ?: return null
        val dateTo = getString("dateTo")?.let { Date.fromY_m_d(it) } ?: dateFrom

        // the API returns objects here - [{"number": 7, "date": "2026-09-07"}] - even though
        // POST /Justifications takes plain numbers
        val lessons = getJsonArray("lessons")
            ?.mapNotNull {
                when {
                    it.isJsonObject -> it.asJsonObject.getInt("number")
                    it.isJsonPrimitive -> it.asInt
                    else -> null
                }
            }
            ?.joinToString(",")

        return LibrusExcuse(
            profileId = profileId,
            id = id,
            dateFrom = dateFrom,
            dateTo = dateTo,
            lessons = lessons,
            message = getString("messageFromParent") ?: "",
            status = LibrusExcuse.statusOf(getString("justificationStatus")),
            justifiedAbsences = getInt("justifiedAbsences") ?: 0,
            // also a list of objects - [{"name": "Kowalska Anna"}]
            notifiedTeachers = getJsonArray("notifiedTeachers")
                ?.mapNotNull {
                    when {
                        it.isJsonObject -> it.asJsonObject.getString("name")
                        it.isJsonPrimitive -> it.asString
                        else -> null
                    }
                }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", "),
            hasAttachment = getBoolean("attachment") ?: false,
            postDate = getString("postDate")?.let { Date.fromIso(it) } ?: System.currentTimeMillis(),
        )
    }
}
