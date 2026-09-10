/*
 * Copyright (c) Kuba Szczodrzyński 2020-5-9.
 */

package pl.szczodrzynski.edziennik.ui.attendance

import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import pl.szczodrzynski.edziennik.App
import pl.szczodrzynski.edziennik.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.szczodrzynski.edziennik.data.db.entity.Attendance
import pl.szczodrzynski.edziennik.data.db.entity.LibrusExcuse
import pl.szczodrzynski.edziennik.data.db.enums.LoginType
import pl.szczodrzynski.edziennik.data.db.full.AttendanceFull
import pl.szczodrzynski.edziennik.databinding.AttendanceDetailsDialogBinding
import pl.szczodrzynski.edziennik.ext.setTintColor
import pl.szczodrzynski.edziennik.ext.onClick
import pl.szczodrzynski.edziennik.ui.dialogs.base.BindingDialog
import pl.szczodrzynski.edziennik.ui.excuses.ExcuseDetailsDialog
import pl.szczodrzynski.edziennik.ui.excuses.ExcuseEditorDialog
import pl.szczodrzynski.edziennik.ui.notes.setupNotesButton
import pl.szczodrzynski.edziennik.utils.BetterLink
import pl.szczodrzynski.edziennik.utils.managers.NoteManager

class AttendanceDetailsDialog(
    activity: AppCompatActivity,
    private val attendance: AttendanceFull,
    private val showNotes: Boolean = true,
    onShowListener: ((tag: String) -> Unit)? = null,
    onDismissListener: ((tag: String) -> Unit)? = null,
) : BindingDialog<AttendanceDetailsDialogBinding>(activity, onShowListener, onDismissListener) {

    override val TAG = "AttendanceDetailsDialog"

    override fun getTitleRes(): Int? = null
    override fun inflate(layoutInflater: LayoutInflater) =
        AttendanceDetailsDialogBinding.inflate(layoutInflater)

    override fun getPositiveButtonText() = R.string.close

    override suspend fun onShow() {
        val manager = app.attendanceManager

        val attendanceColor = manager.getAttendanceColor(attendance)
        b.attendance = attendance
        b.devMode = App.devMode
        b.attendanceName.setTextColor(if (ColorUtils.calculateLuminance(attendanceColor) > 0.3) 0xaa000000.toInt() else 0xccffffff.toInt())
        b.attendanceName.background.setTintColor(attendanceColor)

        b.attendanceIsCounted.setText(if (attendance.isCounted) R.string.yes else R.string.no)

        attendance.teacherName?.let { name ->
            BetterLink.attach(
                b.teacherName,
                teachers = mapOf(attendance.teacherId to name),
                onActionSelected = dialog::dismiss
            )
        }

        setupExcuseButton()

        b.notesButton.isVisible = showNotes
        b.notesButton.setupNotesButton(
            activity = activity,
            owner = attendance,
            onShowListener = onShowListener,
            onDismissListener = onDismissListener,
        )
        b.legend.isVisible = showNotes
        if (showNotes)
            NoteManager.setLegendText(attendance, b.legend)
    }

    /**
     * Librus e-Usprawiedliwienia, parent accounts only: shows the status of the excuse
     * covering this lesson, or - for unexcused absences/lates - lets the parent write one.
     */
    private suspend fun setupExcuseButton() {
        val profile = app.profile
        val lessonNumber = attendance.lessonNumber

        b.excuseButton.isVisible = false
        if (profile == null
            || profile.loginStoreType != LoginType.LIBRUS
            || !profile.isParent
            || profile.config.librusExcusesUnavailable
            || lessonNumber == null)
            return

        val excuse = withContext(Dispatchers.IO) {
            app.db.librusExcuseDao().getForLessonNow(profile.id, attendance.date, lessonNumber)
        }
        if (excuse != null) {
            val statusRes = when (excuse.status) {
                LibrusExcuse.Status.LOCAL -> R.string.excuses_status_local
                LibrusExcuse.Status.SEND -> R.string.excuses_status_send
                LibrusExcuse.Status.ACCEPT -> R.string.excuses_status_accept
                LibrusExcuse.Status.REJECT -> R.string.excuses_status_reject
            }
            b.excuseButton.isVisible = true
            b.excuseButton.text = app.getString(R.string.excuses_attendance_status_format, app.getString(statusRes))
            b.excuseButton.onClick {
                dialog.dismiss()
                ExcuseDetailsDialog(activity, excuse, onShowListener, onDismissListener).show()
            }
            return
        }

        if (attendance.baseType !in setOf(Attendance.TYPE_ABSENT, Attendance.TYPE_BELATED))
            return

        b.excuseButton.isVisible = true
        b.excuseButton.onClick {
            dialog.dismiss()
            ExcuseEditorDialog(
                activity = activity,
                attendance = attendance,
                profileId = attendance.profileId,
                onShowListener = onShowListener,
                onDismissListener = onDismissListener,
            ).show()
        }
    }
}
