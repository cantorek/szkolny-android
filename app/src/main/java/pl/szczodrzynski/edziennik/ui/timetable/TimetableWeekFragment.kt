/*
 * Copyright (c) Kuba Szczodrzyński 2026-9-12.
 */

package pl.szczodrzynski.edziennik.ui.timetable

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.asynclayoutinflater.view.AsyncLayoutInflater
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.szczodrzynski.edziennik.App
import pl.szczodrzynski.edziennik.MainActivity
import pl.szczodrzynski.edziennik.R
import pl.szczodrzynski.edziennik.data.api.edziennik.EdziennikTask
import pl.szczodrzynski.edziennik.data.db.entity.Lesson
import pl.szczodrzynski.edziennik.data.db.enums.FeatureType
import pl.szczodrzynski.edziennik.data.db.full.LessonFull
import pl.szczodrzynski.edziennik.databinding.TimetableNoLessonsBinding
import pl.szczodrzynski.edziennik.databinding.TimetableNoTimetableBinding
import pl.szczodrzynski.edziennik.databinding.TimetableWeekFragmentBinding
import pl.szczodrzynski.edziennik.databinding.TimetableWeekLessonBinding
import pl.szczodrzynski.edziennik.databinding.TimetableWeekRowBinding
import pl.szczodrzynski.edziennik.ext.Intent
import pl.szczodrzynski.edziennik.ext.JsonObject
import pl.szczodrzynski.edziennik.ext.asStrikethroughSpannable
import pl.szczodrzynski.edziennik.ext.dp
import pl.szczodrzynski.edziennik.ext.onClick
import pl.szczodrzynski.edziennik.ext.resolveAttr
import pl.szczodrzynski.edziennik.ext.setText
import pl.szczodrzynski.edziennik.ext.startCoroutineTimer
import pl.szczodrzynski.edziennik.ui.base.lazypager.LazyFragment
import pl.szczodrzynski.edziennik.utils.Colors
import pl.szczodrzynski.edziennik.utils.models.Date
import pl.szczodrzynski.edziennik.utils.models.Time
import pl.szczodrzynski.edziennik.utils.models.Week
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

class TimetableWeekFragment : LazyFragment(), CoroutineScope {

    private lateinit var app: App
    private lateinit var activity: MainActivity
    private lateinit var asyncInflater: AsyncLayoutInflater
    private lateinit var b: TimetableWeekFragmentBinding

    private val job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    private lateinit var weekStart: Date
    private lateinit var weekEnd: Date

    private var viewsRemoved = false

    private var slots = listOf<Slot>()
    private val chips = mutableMapOf<Pair<Int, Int>, MutableList<Pair<TimetableWeekLessonBinding, LessonFull>>>()
    private var highlighted: Pair<Int, Int>? = null
    private var highlightJob: Job? = null

    /** A single row of the week grid - one lesson time slot. */
    private data class Slot(
        val number: Int?,
        val start: Time,
        val end: Time?,
    )

    private class Grid(
        val slots: List<Slot>,
        val maxWeekDay: Int,
        val cells: Map<Pair<Int, Int>, List<LessonFull>>,
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        activity = (getActivity() as MainActivity?) ?: return null
        context ?: return null
        app = activity.application as App
        this.asyncInflater = AsyncLayoutInflater(requireContext())

        weekStart = arguments?.getInt("weekStart")?.let { Date.fromValue(it) }
            ?: Date.getToday().weekStart
        weekEnd = weekStart.weekEnd

        b = TimetableWeekFragmentBinding.inflate(inflater, null, false)
        return b.root
    }

    override fun onPageCreated(): Boolean {
        app.db.timetableDao().getAllForWeek(App.profileId, weekStart, weekEnd)
            .observe(viewLifecycleOwner) { lessons ->
                launch {
                    lessons.forEach { it.filterNotes() }
                    processLessonList(lessons)
                }
            }

        return true
    }

    private suspend fun processLessonList(lessons: List<LessonFull>) {
        // no lessons at all - the week is not downloaded yet
        if (lessons.isEmpty()) {
            showNoTimetable()
            return
        }
        // only days marked as "no lessons" - holidays
        if (lessons.all { it.type == Lesson.TYPE_NO_LESSONS }) {
            showNoLessons()
            return
        }

        // the week was not synced (the grid views are removed) and is now available
        if (viewsRemoved) {
            viewsRemoved = false
            activity.sendBroadcast(Intent(TimetableFragment.ACTION_RELOAD_PAGES))
            return
        }

        val grid = withContext(Dispatchers.Default) { buildGrid(lessons) }
        render(grid)
    }

    private fun showNoTimetable() {
        asyncInflater.inflate(R.layout.timetable_no_timetable, b.weekRoot) { view, _, _ ->
            b.weekRoot.removeAllViews()
            b.weekRoot.addView(view)
            viewsRemoved = true

            val nb = TimetableNoTimetableBinding.bind(view)
            nb.noTimetableSync.onClick {
                it.isEnabled = false
                syncWeek()
            }
            nb.noTimetableWeek.setText(R.string.timetable_no_timetable_week, weekStart.stringY_m_d)
        }
    }

    private fun showNoLessons() {
        asyncInflater.inflate(R.layout.timetable_no_lessons, b.weekRoot) { view, _, _ ->
            b.weekRoot.removeAllViews()
            b.weekRoot.addView(view)
            viewsRemoved = true

            val nb = TimetableNoLessonsBinding.bind(view)
            nb.noLessonsSync.onClick {
                it.isEnabled = false
                syncWeek()
            }
        }
    }

    private fun syncWeek() = EdziennikTask.syncProfile(
        profileId = App.profileId,
        featureTypes = setOf(FeatureType.TIMETABLE),
        arguments = JsonObject(
            "weekStart" to weekStart.stringY_m_d
        )
    ).enqueue(activity)

    private fun buildGrid(lessons: List<LessonFull>): Grid {
        val actual = lessons.filter {
            it.type != Lesson.TYPE_NO_LESSONS && it.displayDate != null && it.displayStartTime != null
        }
        // lesson ranges provide the slot number and the end time labels
        val ranges = app.db.lessonRangeDao().getAllNow(App.profileId)
            .associateBy { it.startTime.value }

        // rows: every distinct lesson start time present in this week
        val slots = actual.mapNotNull { it.displayStartTime?.value }
            .distinct()
            .sorted()
            .map { value ->
                val range = ranges[value]
                val inSlot = actual.filter { it.displayStartTime?.value == value }
                Slot(
                    number = range?.lessonNumber ?: inSlot.firstNotNullOfOrNull { it.displayLessonNumber },
                    start = range?.startTime ?: Time.fromValue(value),
                    end = range?.endTime ?: inSlot.mapNotNull { it.displayEndTime }.maxOrNull()
                )
            }

        // columns: Monday to Friday, plus the weekend only if there are lessons then
        val maxWeekDay = max(Week.FRIDAY, actual.maxOf { it.displayDate!!.weekDay })

        val cells = actual.groupBy { lesson ->
            slots.indexOfFirst { it.start.value == lesson.displayStartTime?.value } to
                    lesson.displayDate!!.weekDay
        }.mapValues { (_, value) ->
            value.distinctBy { it.displaySubjectName to it.displayClassroom }
                .sortedBy { it.type == Lesson.TYPE_CANCELLED }
        }

        return Grid(slots, maxWeekDay, cells)
    }

    private fun render(grid: Grid) {
        if (!isAdded)
            return

        // the LiveData re-emits on every sync
        b.headerRow.removeAllViews()
        b.weekGrid.removeAllViews()
        chips.clear()
        highlighted = null
        slots = grid.slots

        val today = Date.getToday()
        val colorPrimary = R.attr.colorPrimary.resolveAttr(activity)
        val colorSecondary = android.R.attr.textColorSecondary.resolveAttr(activity)

        // day names with dates
        b.headerRow.addView(
            View(activity),
            LinearLayout.LayoutParams(40.dp, LinearLayout.LayoutParams.MATCH_PARENT)
        )
        for (weekDay in 0..grid.maxWeekDay) {
            val date = weekStart.clone().stepForward(0, 0, weekDay)
            val headerView = TextView(activity).apply {
                text = "${Week.getFullDayName(weekDay).take(3)}\n${date.stringDm}"
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
                setTextColor(if (date == today) colorPrimary else colorSecondary)
            }
            b.headerRow.addView(headerView, columnLayoutParams())
        }

        for ((index, slot) in grid.slots.withIndex()) {
            val rb = TimetableWeekRowBinding.inflate(layoutInflater, b.weekGrid, false)
            rb.lessonNumber.text = slot.number?.toString() ?: ""
            rb.timeRange.text = listOfNotNull(slot.start.stringHM, slot.end?.stringHM)
                .joinToString("\n")

            for (weekDay in 0..grid.maxWeekDay) {
                val cellLessons = grid.cells[index to weekDay]
                if (cellLessons == null) {
                    rb.rowLayout.addView(View(activity), columnLayoutParams())
                    continue
                }
                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                for (lesson in cellLessons) {
                    // inflated with the cell as parent, so the XML margins apply
                    val lb = TimetableWeekLessonBinding.inflate(layoutInflater, cell, false)
                    buildLessonView(lb, lesson)
                    chips.getOrPut(index to weekDay) { mutableListOf() } += lb to lesson
                    cell.addView(lb.root)
                }
                rb.rowLayout.addView(cell, columnLayoutParams())
            }

            b.weekGrid.addView(rb.root)
            b.weekGrid.addView(View(activity).apply {
                setBackgroundColor(R.attr.halfHourDividerColor.resolveAttr(activity))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp))
        }

        b.progressBar.isVisible = false

        updateHighlight()
    }

    private fun buildLessonView(lb: TimetableWeekLessonBinding, lesson: LessonFull) {
        val name = lesson.getNoteSubstituteText(showNotes = true)?.toString()
            ?: lesson.displaySubjectName
            ?: "?"

        lb.subjectName.text =
            if (lesson.type == Lesson.TYPE_CANCELLED || lesson.type == Lesson.TYPE_SHIFTED_SOURCE)
                name.asStrikethroughSpannable()
            else
                name

        lb.classroom.isVisible = !lesson.displayClassroom.isNullOrBlank()
        lb.classroom.text = lesson.displayClassroom

        lb.lessonChip.onClick {
            if (isAdded)
                LessonDetailsDialog(activity = activity, lesson = lesson).show()
        }

        styleLessonView(lb, lesson, isHighlighted = false)
    }

    /**
     * Colors the chip and, for the ongoing (or upcoming) lesson,
     * makes it stand out - outlined, raised and slightly larger.
     */
    private fun styleLessonView(
        lb: TimetableWeekLessonBinding,
        lesson: LessonFull,
        isHighlighted: Boolean,
    ) {
        val name = lesson.displaySubjectName ?: ""
        // the lesson change type takes precedence over the subject color
        val color = when (lesson.type) {
            Lesson.TYPE_CANCELLED -> R.attr.timetable_lesson_cancelled_color.resolveAttr(activity)
            Lesson.TYPE_SHIFTED_SOURCE -> R.attr.timetable_lesson_shifted_source_color.resolveAttr(activity)
            Lesson.TYPE_SHIFTED_TARGET -> R.attr.timetable_lesson_shifted_target_color.resolveAttr(activity)
            Lesson.TYPE_CHANGE -> R.attr.timetable_lesson_change_color.resolveAttr(activity)
            else -> lesson.color ?: Colors.stringToMaterialColorCRC(name)
        }

        lb.lessonChip.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4.dp.toFloat()
            setColor(color)
            if (isHighlighted)
                setStroke(2.dp, R.attr.colorPrimary.resolveAttr(activity))
        }
        lb.lessonChip.elevation = if (isHighlighted) 4.dp.toFloat() else 0f

        val (textPrimary, textSecondary) = when (ColorUtils.calculateLuminance(color) > 0.5) {
            true -> /* light */ 0xFF000000.toInt() to 0xFF666666.toInt()
            false -> /* dark */ 0xFFFFFFFF.toInt() to 0xFFDDDDDD.toInt()
        }
        lb.subjectName.setTextColor(textPrimary)
        lb.classroom.setTextColor(textSecondary)

        lb.subjectName.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isHighlighted) 13f else 11f)
        lb.classroom.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isHighlighted) 10f else 9f)
    }

    /**
     * Highlights the lesson taking place right now, or the next one during a break.
     */
    private fun updateHighlight() {
        val today = Date.getToday()
        val target = if (today.weekStart.value != weekStart.value)
            null // not the current week
        else
            findCurrentOrNextLesson(today)

        if (target != highlighted) {
            chips[highlighted]?.forEach { (lb, lesson) ->
                styleLessonView(lb, lesson, isHighlighted = false)
            }
            chips[target]?.forEach { (lb, lesson) ->
                styleLessonView(lb, lesson, isHighlighted = true)
            }
            highlighted = target
        }

        if (highlightJob == null) {
            highlightJob = startCoroutineTimer(repeatMillis = 60000) {
                updateHighlight()
            }
        }
    }

    private fun findCurrentOrNextLesson(today: Date): Pair<Int, Int>? {
        val now = Time.getNow().value
        val weekDay = today.weekDay
        val todaySlots = slots.withIndex().filter { (index, _) ->
            chips.containsKey(index to weekDay)
        }

        val slot = todaySlots.firstOrNull { (_, slot) ->
            // the lesson is taking place now
            slot.start.value <= now && now <= (slot.end?.value ?: slot.start.value)
        } ?: todaySlots.firstOrNull { (_, slot) ->
            // a break or before the lessons - the next one
            slot.start.value > now
        } ?: return null

        return slot.index to weekDay
    }

    private fun columnLayoutParams() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)

    override fun onResume() {
        super.onResume()
        if (chips.isNotEmpty())
            updateHighlight()
    }

    override fun onPause() {
        super.onPause()
        highlightJob?.cancel()
        highlightJob = null
    }
}
