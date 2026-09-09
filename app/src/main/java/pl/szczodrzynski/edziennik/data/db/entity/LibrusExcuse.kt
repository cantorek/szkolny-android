/*
 * Copyright (c) Kuba Szczodrzyński 2026-9-9.
 */
package pl.szczodrzynski.edziennik.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import pl.szczodrzynski.edziennik.utils.models.Date

/**
 * A Librus e-Usprawiedliwienie (absence excuse), sent by a parent.
 *
 * Excuses are linked to attendances by (date, lesson number) - never by attendance ID,
 * because [pl.szczodrzynski.edziennik.data.api.edziennik.librus.data.api.LibrusApiAttendances]
 * strips non-digits from the Librus ID, so it may not map back.
 */
@Entity(
    tableName = "librusExcuses",
    primaryKeys = ["profileId", "excuseId"],
    indices = [
        Index(value = ["profileId", "excuseDateFrom", "excuseDateTo"]),
    ],
)
data class LibrusExcuse(
    val profileId: Int,
    /** The API's excuse ID. Locally created, not yet confirmed excuses get a negative ID. */
    @ColumnInfo(name = "excuseId")
    val id: Long,

    @ColumnInfo(name = "excuseDateFrom")
    val dateFrom: Date,
    @ColumnInfo(name = "excuseDateTo")
    val dateTo: Date,
    /** Comma-separated lesson numbers. Null or empty means whole days. */
    @ColumnInfo(name = "excuseLessons")
    val lessons: String?,
    /** The parent's message. May be empty - Librus does not require it. */
    @ColumnInfo(name = "excuseMessage")
    val message: String,

    @ColumnInfo(name = "excuseStatus")
    val status: Status,
    @ColumnInfo(name = "excuseJustifiedAbsences")
    val justifiedAbsences: Int = 0,
    @ColumnInfo(name = "excuseNotifiedTeachers")
    val notifiedTeachers: String? = null,
    @ColumnInfo(name = "excuseHasAttachment")
    val hasAttachment: Boolean = false,
    @ColumnInfo(name = "excusePostDate")
    val postDate: Long = System.currentTimeMillis(),
) {
    companion object {
        /**
         * Builds an ID for an excuse that is being sent, but not confirmed by Librus yet.
         * Negative, so it can never collide with an API-assigned ID.
         */
        fun localId() = -System.currentTimeMillis()

        fun statusOf(apiValue: String?) = when (apiValue) {
            "accept" -> Status.ACCEPT
            "reject" -> Status.REJECT
            else -> Status.SEND
        }
    }

    enum class Status {
        /** Saved locally, the POST has not been confirmed yet. */
        LOCAL,

        /** Sent, awaiting the teacher's decision. */
        SEND,
        ACCEPT,
        REJECT,
    }

    /** Lesson numbers this excuse covers. Empty means whole days. */
    @delegate:Ignore
    @delegate:Transient
    val lessonNumbers by lazy {
        lessons?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
    }

    val isWholeDays
        get() = lessonNumbers.isEmpty()

    val isRange
        get() = dateFrom != dateTo
}
