/*
 * Copyright (c) Kuba Szczodrzyński 2026-9-9.
 */

package pl.szczodrzynski.edziennik.ui.excuses

import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.szczodrzynski.edziennik.App
import pl.szczodrzynski.edziennik.R
import pl.szczodrzynski.edziennik.data.api.edziennik.EdziennikTask
import pl.szczodrzynski.edziennik.data.db.entity.LibrusExcuse
import pl.szczodrzynski.edziennik.data.db.full.AttendanceFull
import pl.szczodrzynski.edziennik.databinding.ExcuseEditorDialogBinding
import pl.szczodrzynski.edziennik.ui.dialogs.base.BindingDialog
import pl.szczodrzynski.edziennik.utils.TextInputDropDown
import pl.szczodrzynski.edziennik.utils.models.Date

/**
 * Composes and sends a Librus e-Usprawiedliwienie.
 *
 * Mirrors the two scopes Librus itself offers: whole days (a single day or a range),
 * or selected lessons of one day.
 *
 * The message is intentionally optional - Librus accepts excuses with no text at all.
 */
class ExcuseEditorDialog(
    activity: AppCompatActivity,
    private val attendance: AttendanceFull? = null,
    private val profileId: Int = App.profileId,
    onShowListener: ((tag: String) -> Unit)? = null,
    onDismissListener: ((tag: String) -> Unit)? = null,
) : BindingDialog<ExcuseEditorDialogBinding>(activity, onShowListener, onDismissListener) {

    override val TAG = "ExcuseEditorDialog"

    companion object {
        private const val SCOPE_DAYS = 0L
        private const val SCOPE_LESSONS = 1L
    }

    override fun getTitleRes() = R.string.excuses_editor_title
    override fun inflate(layoutInflater: LayoutInflater) =
        ExcuseEditorDialogBinding.inflate(layoutInflater)

    override fun isCancelable() = false
    override fun getPositiveButtonText() = R.string.excuses_editor_send
    override fun getNegativeButtonText() = R.string.cancel

    private val lessonCheckBoxes = mutableListOf<MaterialCheckBox>()

    private val isLessonScope
        get() = b.scopeDropdown.selectedId == SCOPE_LESSONS

    override suspend fun onShow() {
        b.scopeDropdown.items = mutableListOf(
            TextInputDropDown.Item(SCOPE_DAYS, activity.getString(R.string.excuses_scope_days)),
            TextInputDropDown.Item(SCOPE_LESSONS, activity.getString(R.string.excuses_scope_lessons)),
        )

        for (dropdown in listOf(b.dateFromDropdown, b.dateToDropdown)) {
            dropdown.db = app.db
            dropdown.profileId = this@ExcuseEditorDialog.profileId
            dropdown.showWeekDays = false
            dropdown.showDays = true
            dropdown.showOtherDate = true
            dropdown.loadItems()
        }

        val startDate = attendance?.date ?: Date.getToday()
        b.dateFromDropdown.selectDate(startDate)
        b.dateToDropdown.selectDate(startDate)

        b.dateFromDropdown.onDateSelected = { date, _ ->
            if (!isLessonScope && (b.dateToDropdown.getSelected() as? Date)?.let { it < date } == true)
                b.dateToDropdown.selectDate(date)
            updateNotifyState()
            if (isLessonScope)
                launch { loadLessons(date) }
        }
        b.dateToDropdown.onDateSelected = { _, _ -> updateNotifyState() }

        b.scopeDropdown.select(if (attendance != null) SCOPE_LESSONS else SCOPE_DAYS)
        b.scopeDropdown.setOnChangeListener { item ->
            applyScope(item.id == SCOPE_LESSONS)
            true
        }
        applyScope(attendance != null)
    }

    private fun applyScope(lessonScope: Boolean) {
        b.dateToLayout.isVisible = !lessonScope
        b.lessonsLabel.isVisible = lessonScope
        b.lessonsContainer.isVisible = lessonScope
        updateNotifyState()
        if (lessonScope) {
            val date = b.dateFromDropdown.getSelected() as? Date ?: Date.getToday()
            launch { loadLessons(date) }
        } else {
            b.lessonsEmpty.isVisible = false
        }
    }

    /**
     * Builds the lesson checkboxes from the local timetable, falling back to the
     * attendance entries of that day - the official app does the same, so there is
     * no need to ask Librus for the lesson list.
     */
    private suspend fun loadLessons(date: Date) {
        val lessons = withContext(Dispatchers.IO) {
            val timetable = app.db.timetableDao()
                .getAllForDateNow(profileId, date)
                .mapNotNull { lesson ->
                    lesson.displayLessonNumber?.let { it to lesson.displaySubjectName }
                }
            timetable.ifEmpty {
                app.db.attendanceDao()
                    .getAllByDateNow(profileId, date)
                    .mapNotNull { it.lessonNumber?.let { no -> no to it.subjectLongName } }
            }
        }.distinctBy { it.first }.sortedBy { it.first }

        b.lessonsContainer.removeAllViews()
        lessonCheckBoxes.clear()
        b.lessonsEmpty.isVisible = lessons.isEmpty()

        for ((lessonNumber, subjectName) in lessons) {
            val checkBox = MaterialCheckBox(activity).apply {
                text = activity.getString(
                    R.string.excuses_editor_lesson_format,
                    lessonNumber,
                    subjectName ?: "",
                ).trim()
                tag = lessonNumber
                isChecked = attendance?.lessonNumber == lessonNumber
            }
            lessonCheckBoxes += checkBox
            b.lessonsContainer.addView(checkBox)
        }
    }

    /** Librus only allows notifying teachers about absences that have not happened yet. */
    private fun updateNotifyState() {
        val date = (if (isLessonScope) b.dateFromDropdown.getSelected() else b.dateToDropdown.getSelected())
            as? Date ?: Date.getToday()
        val future = date >= Date.getToday()
        b.notifyCheckBox.isEnabled = future
        if (!future)
            b.notifyCheckBox.isChecked = false
    }

    override suspend fun onPositiveClick(): Boolean {
        val dateFrom = b.dateFromDropdown.getSelected() as? Date
        if (dateFrom == null) {
            Toast.makeText(activity, R.string.excuses_editor_no_date, Toast.LENGTH_SHORT).show()
            return NO_DISMISS
        }

        val lessonScope = isLessonScope
        val dateTo = if (lessonScope) dateFrom else b.dateToDropdown.getSelected() as? Date ?: dateFrom

        if (dateTo < dateFrom) {
            Toast.makeText(activity, R.string.excuses_editor_bad_range, Toast.LENGTH_SHORT).show()
            return NO_DISMISS
        }

        val lessons = when {
            lessonScope -> lessonCheckBoxes.filter { it.isChecked }.mapNotNull { it.tag as? Int }
            else -> emptyList()
        }
        if (lessonScope && lessons.isEmpty()) {
            Toast.makeText(activity, R.string.excuses_editor_no_lessons, Toast.LENGTH_SHORT).show()
            return NO_DISMISS
        }

        // NOTE: the message is deliberately not validated - Librus allows sending it empty
        val message = b.messageEdit.text?.toString() ?: ""
        val sendNotify = b.notifyCheckBox.isEnabled && b.notifyCheckBox.isChecked

        withContext(Dispatchers.IO) {
            app.db.librusExcuseDao().add(
                LibrusExcuse(
                    profileId = profileId,
                    id = LibrusExcuse.localId(),
                    dateFrom = dateFrom,
                    dateTo = dateTo,
                    lessons = lessons.joinToString(","),
                    message = message,
                    status = LibrusExcuse.Status.LOCAL,
                )
            )
        }

        EdziennikTask.excuseSend(profileId, dateFrom, dateTo, lessons, message, sendNotify)
            .enqueue(activity)

        return DISMISS
    }
}
