package com.xingheyuzhuan.shiguangschedule.data.repository

import androidx.room.Transaction
import com.xingheyuzhuan.shiguangschedule.data.db.main.Course
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeek
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeekDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlotDao
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport.CourseConfigJsonModel
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport.CourseTableExportModel
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport.CourseTableImportModel
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport.ExportCourseJsonModel
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport.ImportCourseJsonModel
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseImportExport.TimeSlotJsonModel
import com.xingheyuzhuan.shiguangschedule.tool.IcsExportTool
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 课表转换仓库，负责处理课程数据的导入、导出以及 ICS 生成等逻辑。
 */
@Singleton
class CourseConversionRepository @Inject constructor(
    private val courseDao: CourseDao,
    private val courseWeekDao: CourseWeekDao,
    private val timeSlotDao: TimeSlotDao,
    private val appSettingsRepository: AppSettingsRepository,
    private val styleSettingsRepository: StyleSettingsRepository
) {
    /**
     * 内部辅助函数：根据课程名称获取或分配颜色索引。
     */
    private fun getOrAssignColorByName(
        jsonCourse: ImportCourseJsonModel,
        colorSize: Int,
        nameToColorMap: MutableMap<String, Int>,
        getNextAutoColor: () -> Int
    ): Int {
        val trimmedName = jsonCourse.name.trim()
        val existingColor = nameToColorMap[trimmedName]

        if (existingColor != null) return existingColor

        val importedColor = jsonCourse.color
        val finalColor = if (importedColor != null && importedColor in 0 until colorSize) {
            importedColor
        } else {
            getNextAutoColor()
        }

        nameToColorMap[trimmedName] = finalColor
        return finalColor
    }

    /**
     * 从一个 JSON 课程列表导入课程。
     */
    @Transaction
    suspend fun importCoursesFromList(
        tableId: String,
        coursesJsonModel: List<ImportCourseJsonModel>
    ) {
        val currentStyle = styleSettingsRepository.styleFlow.first()
        val colorSize = currentStyle.courseColorMaps.size

        courseDao.deleteCoursesByTableId(tableId)

        val courseEntities = ArrayList<Course>(coursesJsonModel.size)
        val courseWeekEntities = mutableListOf<CourseWeek>()

        val nameToColorMap = mutableMapOf<String, Int>()
        var colorOffset = if (colorSize > 0) Random.nextInt(colorSize) else 0

        coursesJsonModel.forEach { jsonCourse ->
            val courseId = UUID.randomUUID().toString()

            val courseIndex = getOrAssignColorByName(
                jsonCourse = jsonCourse,
                colorSize = colorSize,
                nameToColorMap = nameToColorMap,
                getNextAutoColor = {
                    val next = if (colorSize > 0) colorOffset % colorSize else 0
                    colorOffset++
                    next
                }
            )

            courseEntities.add(
                Course(
                    id = courseId,
                    courseTableId = tableId,
                    name = jsonCourse.name,
                    teacher = jsonCourse.teacher,
                    position = jsonCourse.position,
                    day = jsonCourse.day,
                    startSection = jsonCourse.startSection,
                    endSection = jsonCourse.endSection,
                    isCustomTime = jsonCourse.isCustomTime,
                    customStartTime = jsonCourse.customStartTime,
                    customEndTime = jsonCourse.customEndTime,
                    colorInt = courseIndex,
                    remark = null
                )
            )

            jsonCourse.weeks.forEach { week ->
                courseWeekEntities.add(
                    CourseWeek(courseId = courseId, weekNumber = week)
                )
            }
        }

        if (courseEntities.isNotEmpty()) courseDao.insertAll(courseEntities)
        if (courseWeekEntities.isNotEmpty()) courseWeekDao.insertAll(courseWeekEntities)
    }

    /**
     * 从一个完整的 JSON 模型导入课表数据。
     * 逻辑说明：
     * 1. 课程数据（courses）：始终清空并重新导入。
     * 2. 时间段数据（timeSlots）：仅在 JSON 包含有效数据时覆盖，否则保留本地现状。
     * 3. 配置信息（config）：仅在 JSON 包含有效数据时覆盖，且会保留本地的 showWeekends 设置。
     */
    @Transaction
    suspend fun importCourseTableFromJson(
        tableId: String,
        courseTableJsonModel: CourseTableImportModel
    ) {
        val currentStyle = styleSettingsRepository.styleFlow.first()
        val colorSize = currentStyle.courseColorMaps.size

        // 处理课程数据（始终清空原有课程） ---
        courseDao.deleteCoursesByTableId(tableId)

        val courseEntities = ArrayList<Course>(courseTableJsonModel.courses.size)
        val courseWeekEntities = mutableListOf<CourseWeek>()

        val nameToColorMap = mutableMapOf<String, Int>()
        var colorOffset = if (colorSize > 0) Random.nextInt(colorSize) else 0

        courseTableJsonModel.courses.forEach { jsonCourse ->
            // 如果 JSON 中没给 ID，则生成新的 UUID
            val courseId = jsonCourse.id ?: UUID.randomUUID().toString()

            val courseIndex = getOrAssignColorByName(
                jsonCourse = jsonCourse,
                colorSize = colorSize,
                nameToColorMap = nameToColorMap,
                getNextAutoColor = {
                    val next = if (colorSize > 0) colorOffset % colorSize else 0
                    colorOffset++
                    next
                }
            )

            courseEntities.add(
                Course(
                    id = courseId,
                    courseTableId = tableId,
                    name = jsonCourse.name,
                    teacher = jsonCourse.teacher,
                    position = jsonCourse.position,
                    day = jsonCourse.day,
                    startSection = jsonCourse.startSection,
                    endSection = jsonCourse.endSection,
                    isCustomTime = jsonCourse.isCustomTime,
                    customStartTime = jsonCourse.customStartTime,
                    customEndTime = jsonCourse.customEndTime,
                    colorInt = courseIndex,
                    remark = jsonCourse.remark?.take(300)
                )
            )

            jsonCourse.weeks.forEach { week ->
                courseWeekEntities.add(
                    CourseWeek(courseId = courseId, weekNumber = week)
                )
            }
        }

        // 处理时间段数据（仅在有数据时覆盖） ---
        // 如果 timeSlots 为 null 或空，则完全不触动数据库中的 time_slot 表
        val jsonTimeSlots = courseTableJsonModel.timeSlots
        if (!jsonTimeSlots.isNullOrEmpty()) {
            // 只有确定要导入新时间段时，才删除旧数据
            timeSlotDao.deleteAllTimeSlotsByCourseTableId(tableId)

            val timeSlotEntities = jsonTimeSlots.map { jsonTimeSlot ->
                TimeSlot(
                    number = jsonTimeSlot.number,
                    startTime = jsonTimeSlot.startTime,
                    endTime = jsonTimeSlot.endTime,
                    courseTableId = tableId,
                    alias = jsonTimeSlot.alias
                )
            }
            timeSlotDao.insertAll(timeSlotEntities)
        }

        // 统一执行课程数据插入
        if (courseEntities.isNotEmpty()) courseDao.insertAll(courseEntities)
        if (courseWeekEntities.isNotEmpty()) courseWeekDao.insertAll(courseWeekEntities)

        // 处理配置数据
        val configJson = courseTableJsonModel.config
        if (configJson != null) {
            val currentConfig = appSettingsRepository.getCourseConfigOnce(tableId)
            val updatedConfig = CourseTableConfig(
                courseTableId = tableId,
                showWeekends = currentConfig?.showWeekends ?: false,
                semesterStartDate = configJson.semesterStartDate,
                semesterTotalWeeks = configJson.semesterTotalWeeks,
                defaultClassDuration = configJson.defaultClassDuration,
                defaultBreakDuration = configJson.defaultBreakDuration,
                firstDayOfWeek = configJson.firstDayOfWeek
            )
            appSettingsRepository.insertOrUpdateCourseConfig(updatedConfig)
        }
    }

    /**
     * 导入预设时间段
     */
    @Transaction
    suspend fun importTimeSlots(
        tableId: String,
        timeSlots: List<TimeSlotJsonModel>
    ) {
        val timeSlotEntities = timeSlots.map { jsonModel ->
            TimeSlot(
                number = jsonModel.number,
                startTime = jsonModel.startTime,
                endTime = jsonModel.endTime,
                courseTableId = tableId
            )
        }
        timeSlotDao.deleteAllTimeSlotsByCourseTableId(tableId)
        if (timeSlotEntities.isNotEmpty()) {
            timeSlotDao.insertAll(timeSlotEntities)
        }
    }

    /**
     * 从 JSON 模型更新指定课表的配置。
     */
    @Transaction
    suspend fun importCourseConfig(
        tableId: String,
        configJsonModel: CourseConfigJsonModel
    ) {
        val currentConfig = appSettingsRepository.getCourseConfigOnce(tableId)

        val updatedConfig = CourseTableConfig(
            courseTableId = tableId,
            showWeekends = currentConfig?.showWeekends ?: false,
            semesterStartDate = configJsonModel.semesterStartDate,
            semesterTotalWeeks = configJsonModel.semesterTotalWeeks,
            defaultClassDuration = configJsonModel.defaultClassDuration,
            defaultBreakDuration = configJsonModel.defaultBreakDuration,
            firstDayOfWeek = configJsonModel.firstDayOfWeek
        )

        appSettingsRepository.insertOrUpdateCourseConfig(updatedConfig)
    }

    /**
     * 将指定课表下的所有数据导出为一个完整的 JSON 模型。
     * 包含课程和时间段。
     *
     * @param tableId 要导出的课表的 ID。
     * @return 包含课程和时间段的完整 JSON 模型。
     */
    suspend fun exportCourseTableToJson(tableId: String): CourseTableExportModel? {

        val coursesWithWeeks = courseDao.getCoursesWithWeeksByTableId(tableId).first()
        // 如果找不到课表，直接返回 null
        if (coursesWithWeeks.isEmpty() && appSettingsRepository.getCourseConfigOnce(tableId) == null) {
            return null
        }

        val exportCourses = coursesWithWeeks.map { courseWithWeeks ->
            val course = courseWithWeeks.course
            val weeks = courseWithWeeks.weeks.map { it.weekNumber }
            val colorIndex = course.colorInt

            ExportCourseJsonModel(
                id = course.id,
                name = course.name,
                teacher = course.teacher,
                position = course.position,
                day = course.day,
                startSection = course.startSection,
                endSection = course.endSection,
                color = colorIndex,
                weeks = weeks,
                isCustomTime = course.isCustomTime,
                customStartTime = course.customStartTime,
                customEndTime = course.customEndTime,
                remark = course.remark
            )
        }

        val timeSlots = timeSlotDao.getTimeSlotsByCourseTableId(tableId).first()
        val exportTimeSlots = timeSlots.map { timeSlot ->
            TimeSlotJsonModel(
                number = timeSlot.number,
                startTime = timeSlot.startTime,
                endTime = timeSlot.endTime,
                alias = timeSlot.alias
            )
        }

        // 读取课表配置
        val courseConfig = appSettingsRepository.getCourseConfigOnce(tableId)

        val configToExport = courseConfig ?: CourseTableConfig(courseTableId = tableId)

        // 转换为不含 showWeekends 的 JSON 模型
        val exportConfig = CourseConfigJsonModel(
            semesterStartDate = configToExport.semesterStartDate,
            semesterTotalWeeks = configToExport.semesterTotalWeeks,
            defaultClassDuration = configToExport.defaultClassDuration,
            defaultBreakDuration = configToExport.defaultBreakDuration,
            firstDayOfWeek = configToExport.firstDayOfWeek
        )

        return CourseTableExportModel(
            courses = exportCourses,
            timeSlots = exportTimeSlots,
            config = exportConfig
        )
    }

    /**
     * 将指定课表下的所有课程数据导出为 ICS 日历文件的内容字符串。
     *
     * @param tableId 要导出的课表的 ID。
     * @param alarmMinutes 可选的提醒时间，单位分钟。传入null则不设置提醒。
     * @return 包含 ICS 日历文件内容的字符串，如果失败则返回 null。
     */
    suspend fun exportToIcsString(tableId: String, alarmMinutes: Int?): String? {
        val courses = courseDao.getCoursesWithWeeksByTableId(tableId).first()
        val timeSlots = timeSlotDao.getTimeSlotsByCourseTableId(tableId).first()

        // 从 AppSettings 获取全局设置 (用于 skippedDates)
        val appSettings = appSettingsRepository.getAppSettingsOnce()
        val skippedDates = appSettings.skippedDates

        // 从 CourseTableConfig 获取课表配置 (用于日期和总周数)
        val courseConfig = appSettingsRepository.getCourseConfigOnce(tableId)
        val semesterStartDate = courseConfig?.semesterStartDate?.let { LocalDate.parse(it) }

        // 检查必要配置是否存在
        if (semesterStartDate == null || courseConfig.semesterTotalWeeks <= 0) {
            return null
        }

        return IcsExportTool.generateIcsFileContent(
            courses = courses,
            timeSlots = timeSlots,
            semesterStartDate = semesterStartDate,
            semesterTotalWeeks = courseConfig.semesterTotalWeeks,
            firstDayOfWeekInt = courseConfig.firstDayOfWeek,
            alarmMinutes = alarmMinutes,
            skippedDates = skippedDates
        )
    }
}