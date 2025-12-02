package com.xingheyuzhuan.shiguangschedule.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.xingheyuzhuan.shiguangschedule.MyApplication
import com.xingheyuzhuan.shiguangschedule.data.db.main.AppSettings
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWithWeeks
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseTableRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.TimeSlotRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.DayOfWeek
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 课表中的合并课程块。
 */
data class MergedCourseBlock(
    val day: Int,
    val startSection: Int,
    val endSection: Int,
    val courses: List<CourseWithWeeks>,
    val isConflict: Boolean = false,
    val needsProportionalRendering: Boolean = false // 关键字段：标记是否需要分钟级的比例渲染
)

/**
 * 周课表 UI 的所有状态。
 */
data class WeeklyScheduleUiState(
    val showWeekends: Boolean = false,
    val totalWeeks: Int = 20,
    val timeSlots: List<TimeSlot> = emptyList(),
    val allCourses: List<CourseWithWeeks> = emptyList(),
    val isSemesterSet: Boolean = false,
    val semesterStartDate: LocalDate? = null,
    val firstDayOfWeek: Int = DayOfWeek.MONDAY.value,
    val currentWeekNumber: Int? = null
)

/**
 * 周课表页面的 ViewModel。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeeklyScheduleViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val courseTableRepository: CourseTableRepository,
    private val timeSlotRepository: TimeSlotRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeeklyScheduleUiState())
    val uiState: StateFlow<WeeklyScheduleUiState> = _uiState.asStateFlow()

    private val appSettingsFlow: Flow<AppSettings> = appSettingsRepository.getAppSettings()

    private val courseTableConfigFlow: Flow<CourseTableConfig?> =
        appSettingsFlow.flatMapLatest { settings ->
            settings.currentCourseTableId?.let { tableId ->
                appSettingsRepository.getCourseTableConfigFlow(tableId)
            } ?: flowOf(null)
        }

    private val timeSlotsForCurrentTable: Flow<List<TimeSlot>> =
        appSettingsFlow.flatMapLatest { settings ->
            if (settings.currentCourseTableId != null) {
                timeSlotRepository.getTimeSlotsByCourseTableId(settings.currentCourseTableId)
            } else {
                flowOf(emptyList())
            }
        }

    private val allCourses: Flow<List<CourseWithWeeks>> =
        appSettingsFlow.flatMapLatest { settings ->
            if (settings.currentCourseTableId != null) {
                courseTableRepository.getCoursesWithWeeksByTableId(settings.currentCourseTableId)
            } else {
                flowOf(emptyList())
            }
        }

    init {
        viewModelScope.launch {
            combine(
                appSettingsFlow,
                courseTableConfigFlow,
                timeSlotsForCurrentTable,
                allCourses
            ) { _, config, timeSlots, allCoursesList ->

                val startDateString = config?.semesterStartDate

                val semesterStartDate: LocalDate? = try {
                    startDateString?.let { LocalDate.parse(it) }
                } catch (e: DateTimeParseException) {
                    null
                }

                val isSemesterSet = semesterStartDate != null
                val totalWeeks = config?.semesterTotalWeeks ?: 20
                val firstDayOfWeek = config?.firstDayOfWeek ?: DayOfWeek.MONDAY.value
                val showWeekends = config?.showWeekends ?: false

                val currentWeekNumber = if (semesterStartDate != null) {
                    calculateCurrentWeek(semesterStartDate, totalWeeks, firstDayOfWeek)
                } else {
                    null
                }

                fixInvalidCourseColors(allCoursesList)

                WeeklyScheduleUiState(
                    showWeekends = showWeekends,
                    totalWeeks = totalWeeks,
                    allCourses = allCoursesList,
                    timeSlots = timeSlots,
                    isSemesterSet = isSemesterSet,
                    semesterStartDate = semesterStartDate,
                    firstDayOfWeek = firstDayOfWeek,
                    currentWeekNumber = currentWeekNumber
                )
            }.collect { _uiState.value = it }
        }
    }

    private fun fixInvalidCourseColors(courses: List<CourseWithWeeks>) = viewModelScope.launch {
        val validColorRange = CourseImportExport.COURSE_COLOR_MAPS.indices

        for (courseWithWeeks in courses) {
            val course = courseWithWeeks.course
            val colorInt = course.colorInt

            val isInvalid = colorInt !in validColorRange

            if (isInvalid) {
                val newColorInt = CourseImportExport.getRandomColorIndex()
                courseTableRepository.updateCourseColor(
                    courseId = course.id,
                    newColorInt = newColorInt
                )
            }
        }
    }

    private fun getStartDayOfWeek(date: LocalDate, firstDayOfWeekInt: Int): LocalDate {
        val firstDayOfWeek = DayOfWeek.of(firstDayOfWeekInt)
        return date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
    }

    private fun calculateCurrentWeek(
        semesterStartDate: LocalDate,
        totalWeeks: Int,
        firstDayOfWeekInt: Int
    ): Int? {
        val alignedStartDate = getStartDayOfWeek(semesterStartDate, firstDayOfWeekInt)
        val alignedToday = getStartDayOfWeek(LocalDate.now(), firstDayOfWeekInt)

        if (alignedToday.isBefore(alignedStartDate)) return null

        val diffWeeks = ChronoUnit.WEEKS.between(alignedStartDate, alignedToday).toInt()
        val calculatedWeek = diffWeeks + 1

        return if (calculatedWeek in 1..totalWeeks) calculatedWeek else null
    }
}

/**
 * 合并课程块，处理连续课程和冲突课程。
 */
fun mergeCourses(
    courses: List<CourseWithWeeks>,
    timeSlots: List<TimeSlot>
): List<MergedCourseBlock> {
    val mergedBlocks = mutableListOf<MergedCourseBlock>()

    // 1. 预处理课程：确保所有课程都有非空的节次。自定义课程使用虚拟节次来通过排序。
    val processedCourses = courses.mapNotNull { courseWithWeeks ->
        val c = courseWithWeeks.course

        if (c.isCustomTime) {
            // 自定义课程：必须分配一个非空值来避免后续的排序和合并逻辑崩溃。
            // 节次 1-1 仅是占位符，最终渲染高度和位置由 customTime 决定。
            val start = c.startSection ?: 1
            val end = c.endSection ?: 1

            courseWithWeeks.copy(
                course = c.copy(
                    startSection = start,
                    endSection = end
                )
            )
        } else {
            // 标准课程：必须有非空的节次，否则丢弃。
            if (c.startSection == null || c.endSection == null) {
                return@mapNotNull null
            }
            courseWithWeeks
        }
    }

    val filteredCourses = processedCourses

    // 2. 按天分组并进行合并
    val coursesByDay = filteredCourses
        .filter { it.course.day in 1..7 }
        .groupBy { it.course.day }

    for ((day, dailyCourses) in coursesByDay) {
        // 必须基于节次进行排序 (使用非空断言，因为前面已经保证非空)
        val coursesSorted = dailyCourses.sortedBy { it.course.startSection!! }
        val processedInDay = mutableSetOf<CourseWithWeeks>()

        for (course in coursesSorted) {
            if (!processedInDay.contains(course)) {
                val combinedCourses = mutableListOf(course)

                // 必须基于节次进行冲突检测 (使用非空断言)
                var currentStartSection = course.course.startSection!!
                var currentEndSection = course.course.endSection!!
                var isConflict = false

                val overlappingCourses = coursesSorted.filter { other ->
                    other != course &&
                            !(other.course.endSection!! < currentStartSection ||
                                    other.course.startSection!! > currentEndSection)
                }

                if (overlappingCourses.isNotEmpty()) {
                    isConflict = true
                    combinedCourses.addAll(overlappingCourses)
                    currentStartSection = combinedCourses.minOf { it.course.startSection!! }
                    currentEndSection = combinedCourses.maxOf { it.course.endSection!! }
                }

                // 核心：只要合并块中有一个是自定义时间，就启用比例渲染标记
                val needsProportionalRendering = combinedCourses.any { it.course.isCustomTime }

                mergedBlocks.add(
                    MergedCourseBlock(
                        day = day,
                        startSection = currentStartSection,
                        endSection = currentEndSection,
                        courses = combinedCourses.distinct(),
                        isConflict = isConflict,
                        needsProportionalRendering = needsProportionalRendering
                    )
                )

                processedInDay.addAll(combinedCourses)
            }
        }
    }
    return mergedBlocks
}


object WeeklyScheduleViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])

        if (modelClass.isAssignableFrom(WeeklyScheduleViewModel::class.java)) {
            val myApplication = application as MyApplication
            @Suppress("UNCHECKED_CAST")
            return WeeklyScheduleViewModel(
                appSettingsRepository = myApplication.appSettingsRepository,
                courseTableRepository = myApplication.courseTableRepository,
                timeSlotRepository = myApplication.timeSlotRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}