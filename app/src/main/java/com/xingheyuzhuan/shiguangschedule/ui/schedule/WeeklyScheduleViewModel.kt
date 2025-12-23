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
import com.xingheyuzhuan.shiguangschedule.data.model.ScheduleGridStyle
import com.xingheyuzhuan.shiguangschedule.data.repository.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
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
    val style: ScheduleGridStyle = ScheduleGridStyle(),
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
    private val timeSlotRepository: TimeSlotRepository,
    private val styleSettingsRepository: StyleSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeeklyScheduleUiState())
    val uiState: StateFlow<WeeklyScheduleUiState> = _uiState.asStateFlow()

    // 获取全局设置流
    private val appSettingsFlow: Flow<AppSettings> = appSettingsRepository.getAppSettings()

    // 获取当前样式流（用于动态获取颜色池大小、UI 样式配置）
    private val styleFlow: Flow<ScheduleGridStyle> = styleSettingsRepository.styleFlow

    private val courseTableConfigFlow: Flow<CourseTableConfig?> =
        appSettingsFlow.flatMapLatest { settings ->
            settings.currentCourseTableId?.let { tableId ->
                appSettingsRepository.getCourseTableConfigFlow(tableId)
            } ?: flowOf(null)
        }

    private val timeSlotsForCurrentTable: Flow<List<TimeSlot>> =
        appSettingsFlow.flatMapLatest { settings ->
            settings.currentCourseTableId?.let { tableId ->
                timeSlotRepository.getTimeSlotsByCourseTableId(tableId)
            } ?: flowOf(emptyList())
        }

    private val allCourses: Flow<List<CourseWithWeeks>> =
        appSettingsFlow.flatMapLatest { settings ->
            settings.currentCourseTableId?.let { tableId ->
                courseTableRepository.getCoursesWithWeeksByTableId(tableId)
            } ?: flowOf(emptyList())
        }

    init {
        viewModelScope.launch {
            combine(
                appSettingsFlow,
                courseTableConfigFlow,
                timeSlotsForCurrentTable,
                allCourses,
                styleFlow
            ) { _, config, timeSlots, allCoursesList, currentStyle ->

                val semesterStartDate = try {
                    config?.semesterStartDate?.let { LocalDate.parse(it) }
                } catch (e: DateTimeParseException) {
                    null
                }

                val totalWeeks = config?.semesterTotalWeeks ?: 20
                val firstDayOfWeek = config?.firstDayOfWeek ?: DayOfWeek.MONDAY.value
                val showWeekends = config?.showWeekends ?: false

                val currentWeekNumber = semesterStartDate?.let {
                    calculateCurrentWeek(it, totalWeeks, firstDayOfWeek)
                }

                fixInvalidCourseColors(allCoursesList, currentStyle)

                WeeklyScheduleUiState(
                    style = currentStyle,
                    showWeekends = showWeekends,
                    totalWeeks = totalWeeks,
                    allCourses = allCoursesList,
                    timeSlots = timeSlots,
                    isSemesterSet = semesterStartDate != null,
                    semesterStartDate = semesterStartDate,
                    firstDayOfWeek = firstDayOfWeek,
                    currentWeekNumber = currentWeekNumber
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    /**
     * 优化点：使用动态样式的 indices 进行校验，避免静态常量失效导致的颜色错乱。
     */
    private fun fixInvalidCourseColors(courses: List<CourseWithWeeks>, style: ScheduleGridStyle) {
        viewModelScope.launch {
            val validRange = style.courseColorMaps.indices

            courses.forEach { courseWithWeeks ->
                val course = courseWithWeeks.course
                // 如果当前课程的颜色索引超出了当前样式定义的颜色池范围，重置为一个合法的随机颜色
                if (course.colorInt !in validRange) {
                    val newColorIndex = style.generateRandomColorIndex()
                    courseTableRepository.updateCourseColor(course.id, newColorIndex)
                }
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
            val start = c.startSection ?: 1
            val end = c.endSection ?: 1

            courseWithWeeks.copy(
                course = c.copy(
                    startSection = start,
                    endSection = end
                )
            )
        } else {
            if (c.startSection == null || c.endSection == null) {
                return@mapNotNull null
            }
            courseWithWeeks
        }
    }

    // 2. 按天分组并进行合并
    val coursesByDay = processedCourses
        .filter { it.course.day in 1..7 }
        .groupBy { it.course.day }

    for ((day, dailyCourses) in coursesByDay) {
        val coursesSorted = dailyCourses.sortedBy { it.course.startSection!! }
        val processedInDay = mutableSetOf<CourseWithWeeks>()

        for (course in coursesSorted) {
            if (!processedInDay.contains(course)) {
                val combinedCourses = mutableListOf(course)

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

/**
 * ViewModel 工厂类
 */
object WeeklyScheduleViewModelFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as MyApplication

        if (modelClass.isAssignableFrom(WeeklyScheduleViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WeeklyScheduleViewModel(
                appSettingsRepository = application.appSettingsRepository,
                courseTableRepository = application.courseTableRepository,
                timeSlotRepository = application.timeSlotRepository,
                styleSettingsRepository = application.styleSettingsRepository
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}