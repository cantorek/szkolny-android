/*
 * Copyright (c) Kuba Szczodrzyński 2019-9-29.
 */

package pl.szczodrzynski.edziennik.data.api.interfaces

import com.google.gson.JsonObject
import pl.szczodrzynski.edziennik.data.db.entity.Teacher
import pl.szczodrzynski.edziennik.data.db.enums.FeatureType
import pl.szczodrzynski.edziennik.data.db.full.AnnouncementFull
import pl.szczodrzynski.edziennik.data.db.full.EventFull
import pl.szczodrzynski.edziennik.data.db.full.MessageFull
import pl.szczodrzynski.edziennik.utils.models.Date

interface EdziennikInterface {
    fun sync(featureTypes: Set<FeatureType>? = null, onlyEndpoints: Set<Int>? = null, arguments: JsonObject? = null)
    fun getMessage(message: MessageFull)
    fun sendMessage(recipients: Set<Teacher>, subject: String, text: String)
    fun sendExcuse(dateFrom: Date, dateTo: Date, lessons: List<Int>, message: String, sendNotify: Boolean)
    fun markAllAnnouncementsAsRead()
    fun getAnnouncement(announcement: AnnouncementFull)
    fun getAttachment(owner: Any, attachmentId: Long, attachmentName: String)
    fun getRecipientList()
    fun getEvent(eventFull: EventFull)
    fun firstLogin()
    fun cancel()
}
