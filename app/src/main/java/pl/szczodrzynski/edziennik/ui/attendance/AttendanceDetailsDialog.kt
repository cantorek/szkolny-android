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
import pl.szczodrzynski.edziennik.data.db.enums.LoginType
import pl.szczodrzynski.edziennik.data.db.full.AttendanceFull
import pl.szczodrzynski.edziennik.databinding.AttendanceDetailsDialogBinding
import pl.szczodrzynski.edziennik.ext.setTintColor
import pl.szczodrzynski.edziennik.ext.onClick
import pl.szczodrzynski.edziennik.ui.dialogs.base.BindingDialog
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
     * Librus e-Usprawiedliwienia: parent accounts only, only for unexcused absences/lates,
     * and only if this lesson is not already covered by an excuse.
     */
    private suspend fun setupExcuseButton() {
        val profile = app.profile
        val lessonNumber = attendance.lessonNumber

        val canExcuse = profile != null
            && profile.loginStoreType == LoginType.LIBRUS
            && profile.isParent
            && !profile.config.librusExcusesUnavailable
            && lessonNumber != null
            && attendance.baseType in setOf(Attendance.TYPE_ABSENT, Attendance.TYPE_BELATED)
            && withContext(Dispatchers.IO) {
                app.db.librusExcuseDao()
                    .getForLessonNow(profile.id, attendance.date, lessonNumber) == null
            }

        b.excuseButton.isVisible = canExcuse
        if (!canExcuse)
            return

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
