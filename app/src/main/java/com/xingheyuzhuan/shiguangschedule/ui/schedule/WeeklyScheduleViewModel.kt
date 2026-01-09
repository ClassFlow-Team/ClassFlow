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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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


// 时间格式化器，用于解析 "HH:mm" 字符串
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 辅助数据类，用于在合并前将所有课程（无论类型）统一为包含精确时间范围的对象。
 */
private data class NormalizableCourse(
    val courseWithWeeks: CourseWithWeeks,
    val startTime: LocalTime,
    val endTime: LocalTime
)

/**
 * 合并课程块，处理连续课程和冲突课程。
 */
fun mergeCourses(
    courses: List<CourseWithWeeks>,
    timeSlots: List<TimeSlot>
): List<MergedCourseBlock> {
    if (timeSlots.isEmpty() && courses.any { it.course.isCustomTime }) {
        return emptyList()
    }

    val normalizableCourses = courses.mapNotNull { courseWithWeeks ->
        val c = courseWithWeeks.course
        try {
            val (startTime, endTime) = if (c.isCustomTime) {
                Pair(
                    LocalTime.parse(c.customStartTime, TIME_FORMATTER),
                    LocalTime.parse(c.customEndTime, TIME_FORMATTER)
                )
            } else {
                val startSlot = timeSlots.find { it.number == c.startSection }
                val endSlot = timeSlots.find { it.number == c.endSection }
                if (startSlot == null || endSlot == null) return@mapNotNull null
                Pair(
                    LocalTime.parse(startSlot.startTime, TIME_FORMATTER),
                    LocalTime.parse(endSlot.endTime, TIME_FORMATTER)
                )
            }
            NormalizableCourse(courseWithWeeks, startTime, endTime)
        } catch (e: Exception) {
            null
        }
    }

    val mergedBlocks = mutableListOf<MergedCourseBlock>()
    val coursesByDay = normalizableCourses
        .filter { it.courseWithWeeks.course.day in 1..7 }
        .groupBy { it.courseWithWeeks.course.day }

    for ((day, dailyCourses) in coursesByDay) {
        val coursesSorted = dailyCourses.sortedBy { it.startTime }
        val processedCourses = mutableSetOf<NormalizableCourse>()

        for (baseCourse in coursesSorted) {
            if (baseCourse in processedCourses) continue

            val overlappingGroup = coursesSorted.filter { otherCourse ->
                baseCourse.startTime < otherCourse.endTime && baseCourse.endTime > otherCourse.startTime
            }

            val startSection: Int
            val endSection: Int

            val containsOnlyCustom = overlappingGroup.all { it.courseWithWeeks.course.isCustomTime }

            if (containsOnlyCustom && timeSlots.isNotEmpty()) {
                val blockStartTime = overlappingGroup.minOf { it.startTime }
                val blockEndTime = overlappingGroup.maxOf { it.endTime }

                val getSectionForTime = { time: LocalTime ->
                    val sortedSlots = timeSlots.sortedBy { it.number }

                    val directMatch = sortedSlots.find { slot ->
                        val slotStart = LocalTime.parse(slot.startTime, TIME_FORMATTER)
                        val slotEnd = LocalTime.parse(slot.endTime, TIME_FORMATTER)
                        !time.isBefore(slotStart) && time.isBefore(slotEnd)
                    }
                    directMatch?.number
                        ?: (sortedSlots.lastOrNull { slot ->
                            val slotStart = LocalTime.parse(slot.startTime, TIME_FORMATTER)
                            !time.isBefore(slotStart) // 时间晚于或等于该节次开始
                        }?.number ?: 1)
                }

                val endSectionTime = if (blockEndTime == LocalTime.MIN) LocalTime.MIN else blockEndTime.minusNanos(1)

                startSection = getSectionForTime(blockStartTime)
                endSection = getSectionForTime(endSectionTime)

            } else {
                startSection = overlappingGroup.mapNotNull { it.courseWithWeeks.course.startSection }.minOrNull() ?: 1
                endSection = overlappingGroup.mapNotNull { it.courseWithWeeks.course.endSection }.maxOrNull() ?: startSection
            }

            val isConflict = overlappingGroup.size > 1
            val needsProportionalRendering = overlappingGroup.any { it.courseWithWeeks.course.isCustomTime } && (startSection < endSection)

            mergedBlocks.add(
                MergedCourseBlock(
                    day = day,
                    startSection = startSection,
                    endSection = endSection,
                    courses = overlappingGroup.map { it.courseWithWeeks }.distinct(),
                    isConflict = isConflict,
                    needsProportionalRendering = needsProportionalRendering
                )
            )
            processedCourses.addAll(overlappingGroup)
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