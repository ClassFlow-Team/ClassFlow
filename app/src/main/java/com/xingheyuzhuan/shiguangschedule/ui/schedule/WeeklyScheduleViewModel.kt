package com.xingheyuzhuan.shiguangschedule.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.xingheyuzhuan.shiguangschedule.MyApplication
import com.xingheyuzhuan.shiguangschedule.R
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
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 课表展示块：封装单次课程或多个冲突课程。
 * 用于 UI 渲染时决定位置（节次范围）及是否触发冲突处理逻辑。
 */
data class MergedCourseBlock(
    val day: Int,
    val startSection: Int,
    val endSection: Int,
    val courses: List<CourseWithWeeks>, // 包含该位置的所有课程（1个为正常，多个为冲突）
    val isConflict: Boolean = false,
    val needsProportionalRendering: Boolean = false // 标记是否包含自定义时间课程，需根据时间比例精确绘图
)

/**
 * 周课表界面状态快照：驱动 Compose UI 的唯一真相源。
 */
data class WeeklyScheduleUiState(
    val style: ScheduleGridStyle = ScheduleGridStyle(),
    val showWeekends: Boolean = false,
    val totalWeeks: Int = 20,
    val timeSlots: List<TimeSlot> = emptyList(),
    val currentMergedCourses: List<MergedCourseBlock> = emptyList(), // 当前周经过合并处理后的课程列表
    val isSemesterSet: Boolean = false,
    val semesterStartDate: LocalDate? = null,
    val firstDayOfWeek: Int = DayOfWeek.MONDAY.value,
    val weekIndexInPager: Int? = null, // 当前 Pager 页面对应的学期周次
    val weekTitle: String = "",        // 顶部栏标题（如：第5周、暑假中）
    val currentWeekNumber: Int? = null, // 今天的物理周次
    val pagerMondayDate: LocalDate = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
)

@OptIn(ExperimentalCoroutinesApi::class)
class WeeklyScheduleViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val courseTableRepository: CourseTableRepository,
    private val timeSlotRepository: TimeSlotRepository,
    private val styleSettingsRepository: StyleSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeeklyScheduleUiState())
    val uiState: StateFlow<WeeklyScheduleUiState> = _uiState.asStateFlow()

    // 状态驱动源：UI 交互触发的基准日期（当前 Pager 所在的周一）
    private val _pagerMondayDate = MutableStateFlow(
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    )

    private val appSettingsFlow = appSettingsRepository.getAppSettings()
    private val styleFlow = styleSettingsRepository.styleFlow

    // 动态监听当前激活的课表配置（如开学时间、显示周末等）
    private val courseTableConfigFlow = appSettingsFlow.flatMapLatest { settings ->
        settings.currentCourseTableId?.let { tableId ->
            appSettingsRepository.getCourseTableConfigFlow(tableId)
        } ?: flowOf(null)
    }

    // 动态监听当前课表的时间段（节次）定义
    private val timeSlotsFlow = appSettingsFlow.flatMapLatest { settings ->
        settings.currentCourseTableId?.let { tableId ->
            timeSlotRepository.getTimeSlotsByCourseTableId(tableId)
        } ?: flowOf(emptyList())
    }

    // 核心流：当基准日期、设置或配置改变时，从数据库重新拉取对应的课程数据
    private val currentCoursesFlow = combine(
        _pagerMondayDate,
        appSettingsFlow,
        courseTableConfigFlow
    ) { date, settings, config ->
        Triple(date, settings.currentCourseTableId, config)
    }.flatMapLatest { (date, tableId, config) ->
        if (tableId != null && config != null) {
            courseTableRepository.getCoursesWithWeeksByDate(tableId, date, config)
        } else {
            flowOf(emptyList())
        }
    }

    // 外部注入的字符串资源提供者，用于在 ViewModel 中生成多语言标题
    private var stringProvider: ((Int, Array<out Any>) -> String)? = null

    fun setStringProvider(provider: (Int, Array<out Any>) -> String) {
        this.stringProvider = provider
    }

    init {
        viewModelScope.launch {
            // 阶段一：合并配置相关的流（解决 combine 超过 5 个参数限制）
            val configAndTimeFlow = combine(
                appSettingsFlow,
                courseTableConfigFlow,
                styleFlow,
                _pagerMondayDate
            ) { settings, config, style, mondayDate ->
                ScheduleConfigPackage(settings, config, style, mondayDate)
            }

            // 阶段二：合并数据流并产出最终 UI 状态
            combine(
                configAndTimeFlow,
                currentCoursesFlow,
                timeSlotsFlow
            ) { configPkg, courses, timeSlots ->
                val config = configPkg.config
                val startDate = config?.semesterStartDate?.let { LocalDate.parse(it) }
                val firstDayOfWeekInt = config?.firstDayOfWeek ?: DayOfWeek.MONDAY.value
                val totalWeeks = config?.semesterTotalWeeks ?: 20

                // 计算物理本周周次
                val currentWeekNum = appSettingsRepository.getWeekIndexAtDate(
                    targetDate = LocalDate.now(),
                    startDateStr = config?.semesterStartDate,
                    firstDayOfWeekInt = firstDayOfWeekInt
                )

                // 计算当前 Pager 页面显示的周次
                val weekIndex = appSettingsRepository.getWeekIndexAtDate(
                    targetDate = configPkg.mondayDate,
                    startDateStr = config?.semesterStartDate,
                    firstDayOfWeekInt = firstDayOfWeekInt
                )

                val title = generateTitle(weekIndex, startDate, totalWeeks)

                // 自动修正：若课程颜色索引超出当前样式的色板范围，则重置颜色
                fixInvalidCourseColors(courses, configPkg.style)

                WeeklyScheduleUiState(
                    style = configPkg.style,
                    showWeekends = config?.showWeekends ?: false,
                    totalWeeks = totalWeeks,
                    currentMergedCourses = mergeCourses(courses, timeSlots),
                    timeSlots = timeSlots,
                    isSemesterSet = startDate != null,
                    semesterStartDate = startDate,
                    firstDayOfWeek = firstDayOfWeekInt,
                    weekIndexInPager = weekIndex,
                    weekTitle = title,
                    currentWeekNumber = currentWeekNum,
                    pagerMondayDate = configPkg.mondayDate
                )
            }.collect { _uiState.value = it }
        }
    }

    /**
     * 生成动态标题逻辑：根据开学日期和当前周次判断显示内容
     */
    private fun generateTitle(weekIndex: Int?, startDate: LocalDate?, totalWeeks: Int): String {
        val today = LocalDate.now()
        val provider = stringProvider ?: return "..."

        return when {
            startDate == null -> provider(R.string.title_semester_not_set, emptyArray())
            today.isBefore(startDate) -> {
                val days = ChronoUnit.DAYS.between(today, startDate)
                provider(R.string.title_vacation_until_start, arrayOf(days.toString()))
            }
            weekIndex != null && weekIndex in 1..totalWeeks -> {
                provider(R.string.title_current_week, arrayOf(weekIndex.toString()))
            }
            else -> provider(R.string.title_vacation, emptyArray())
        }
    }

    /**
     * 更新当前页面基准日期（Pager 滑动时调用）
     */
    fun updatePagerDate(newDate: LocalDate) {
        _pagerMondayDate.value = newDate
    }

    /**
     * 校验并更新无效的课程颜色索引，确保 UI 渲染不溢出
     */
    private fun fixInvalidCourseColors(courses: List<CourseWithWeeks>, style: ScheduleGridStyle) {
        viewModelScope.launch {
            val validRange = style.courseColorMaps.indices
            courses.forEach { courseWithWeeks ->
                if (courseWithWeeks.course.colorInt !in validRange) {
                    val newIndex = style.generateRandomColorIndex()
                    courseTableRepository.updateCourseColor(courseWithWeeks.course.id, newIndex)
                }
            }
        }
    }
}

/**
 * 内部配置封装类：封装 Settings、Config、Style 等对象，用于 Flow 组合
 */
private data class ScheduleConfigPackage(
    val settings: com.xingheyuzhuan.shiguangschedule.data.db.main.AppSettings,
    val config: com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig?,
    val style: ScheduleGridStyle,
    val mondayDate: LocalDate
)

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 核心算法：合并重叠课程。
 * 将原始课程列表转换为 MergedCourseBlock，处理冲突课程并计算其在格子中的节次范围。
 */
fun mergeCourses(courses: List<CourseWithWeeks>, timeSlots: List<TimeSlot>): List<MergedCourseBlock> {
    if (timeSlots.isEmpty() && courses.any { it.course.isCustomTime }) return emptyList()

    // 步骤1：标准化处理，将所有课程（无论节次还是自定义时间）统一为物理时间段 (Start, End)
    val normalized = courses.mapNotNull { courseWithWeeks ->
        val c = courseWithWeeks.course
        try {
            val (start, end) = if (c.isCustomTime) {
                LocalTime.parse(c.customStartTime, TIME_FORMATTER) to LocalTime.parse(c.customEndTime, TIME_FORMATTER)
            } else {
                val sSlot = timeSlots.find { it.number == c.startSection }
                val eSlot = timeSlots.find { it.number == c.endSection }
                if (sSlot == null || eSlot == null) return@mapNotNull null
                LocalTime.parse(sSlot.startTime, TIME_FORMATTER) to LocalTime.parse(eSlot.endTime, TIME_FORMATTER)
            }
            Triple(courseWithWeeks, start, end)
        } catch (e: Exception) { null }
    }

    val mergedBlocks = mutableListOf<MergedCourseBlock>()
    val byDay = normalized.filter { it.first.course.day in 1..7 }.groupBy { it.first.course.day }

    // 步骤2：按天处理，检测时间重叠并分组
    for ((day, daily) in byDay) {
        val sorted = daily.sortedBy { it.second } // 按开始时间排序
        val processed = mutableSetOf<String>()

        sorted.forEach { base ->
            if (base.first.course.id in processed) return@forEach

            // 寻找所有与当前 base 课程时间存在交集的课程
            val overlaps = sorted.filter { it.second < base.third && it.third > base.second }
            val startSec: Int
            val endSec: Int

            if (overlaps.all { it.first.course.isCustomTime } && timeSlots.isNotEmpty()) {
                // 特殊处理：全为自定义时间的课程冲突，需反向推算其应占据的常规节次格子
                val bStart = overlaps.minOf { it.second }
                val bEnd = overlaps.maxOf { it.third }
                val findSec = { t: LocalTime ->
                    val slots = timeSlots.sortedBy { it.number }
                    slots.find { !t.isBefore(LocalTime.parse(it.startTime, TIME_FORMATTER)) && t.isBefore(LocalTime.parse(it.endTime, TIME_FORMATTER)) }?.number
                        ?: slots.lastOrNull { !t.isBefore(LocalTime.parse(it.startTime, TIME_FORMATTER)) }?.number ?: 1
                }
                startSec = findSec(bStart)
                endSec = findSec(if (bEnd == LocalTime.MIN) LocalTime.MIN else bEnd.minusNanos(1))
            } else {
                // 常规课程：取重叠组中最小开始节次和最大结束节次作为块边界
                startSec = overlaps.mapNotNull { it.first.course.startSection }.minOrNull() ?: 1
                endSec = overlaps.mapNotNull { it.first.course.endSection }.maxOrNull() ?: startSec
            }

            mergedBlocks.add(MergedCourseBlock(
                day = day,
                startSection = startSec,
                endSection = endSec,
                courses = overlaps.map { it.first }.distinct(),
                isConflict = overlaps.size > 1,
                needsProportionalRendering = overlaps.any { it.first.course.isCustomTime } && (startSec < endSec)
            ))
            processed.addAll(overlaps.map { it.first.course.id })
        }
    }
    return mergedBlocks
}



object WeeklyScheduleViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        val app = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as MyApplication
        return WeeklyScheduleViewModel(
            app.appSettingsRepository,
            app.courseTableRepository,
            app.timeSlotRepository,
            app.styleSettingsRepository) as T
    }
}