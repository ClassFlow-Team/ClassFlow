package com.xingheyuzhuan.shiguangschedule.ui.schedule.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import com.xingheyuzhuan.shiguangschedule.ui.schedule.MergedCourseBlock
import androidx.compose.ui.res.stringArrayResource
import com.xingheyuzhuan.shiguangschedule.R

/**
 * 渲染课表网格的 UI 组件。
 */
@Composable
fun ScheduleGrid(
    dates: List<String>,
    timeSlots: List<TimeSlot>,
    mergedCourses: List<MergedCourseBlock>,
    showWeekends: Boolean,
    todayIndex: Int,
    firstDayOfWeek: Int,
    onCourseBlockClicked: (MergedCourseBlock) -> Unit,
    onGridCellClicked: (Int, Int) -> Unit,
    onTimeSlotClicked: () -> Unit
) {
    val weekDays = stringArrayResource(R.array.week_days_full_names).toList()
    val reorderedWeekDays = rearrangeDays(weekDays, firstDayOfWeek)
    val displayDays = if (showWeekends) reorderedWeekDays else reorderedWeekDays.take(5)
    val displayDayCount = displayDays.size

    val density = LocalDensity.current
    val screenWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val timeColumnWidth = ScheduleGridDefaults.TimeColumnWidth
    val dayHeaderHeight = ScheduleGridDefaults.DayHeaderHeight
    val sectionHeight = ScheduleGridDefaults.SectionHeight
    val gridLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

    val gridWidth = screenWidth - timeColumnWidth
    val cellWidth = gridWidth / displayDayCount

    val timeSlotsCount = timeSlots.size
    val totalGridContentHeight = timeSlotsCount * sectionHeight

    val sectionHeightPx = with(density) { sectionHeight.toPx() }


    Column(modifier = Modifier.fillMaxSize()) {
        DayHeader(
            displayDays = displayDays,
            dates = dates,
            cellWidth = cellWidth,
            timeColumnWidth = timeColumnWidth,
            dayHeaderHeight = dayHeaderHeight,
            todayIndex = todayIndex,
            gridLineColor = gridLineColor
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            TimeColumn(
                timeSlots = timeSlots,
                timeColumnWidth = timeColumnWidth,
                sectionHeight = sectionHeight,
                onTimeSlotClicked = onTimeSlotClicked,
                modifier = Modifier.height(totalGridContentHeight)
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                // 背景层：网格线
                GridLines(
                    dayCount = displayDayCount,
                    timeSlotsCount = timeSlotsCount,
                    cellWidth = cellWidth,
                    gridHeight = totalGridContentHeight,
                    sectionHeight = sectionHeight,
                    gridLineColor = gridLineColor
                )

                // 可点击的空白区域
                ClickableGrid(
                    dayCount = displayDayCount,
                    timeSlotsCount = timeSlotsCount,
                    sectionHeight = sectionHeight,
                    onGridCellClicked = { displayIndex, section ->
                        val originalDay = mapDisplayIndexToDay(displayIndex, firstDayOfWeek)
                        onGridCellClicked(originalDay, section)
                    }
                )

                // 浮动层：课程块渲染
                mergedCourses.forEach { mergedBlock ->
                    val newDayIndex = mapDayToDisplayIndex(mergedBlock.day, firstDayOfWeek, showWeekends)

                    // 只有当课程在显示的列范围内时才绘制
                    if (newDayIndex != -1) {
                        // 使用 0-based 的索引计算 offsetX
                        val offsetX = newDayIndex * cellWidth

                        val (offsetYPx, heightPx) = if (mergedBlock.needsProportionalRendering) {
                            calculateProportionalLayout(mergedBlock, timeSlots, sectionHeightPx)
                        } else {
                            calculateFixedLayout(mergedBlock, sectionHeightPx)
                        }

                        // 将像素值转换回 Dp，用于 Compose UI 布局
                        val offsetY = with(density) { offsetYPx.toDp() }
                        val blockHeight = with(density) { heightPx.toDp() }

                        Box(
                            modifier = Modifier
                                .offset(x = offsetX, y = offsetY)
                                .size(width = cellWidth, height = blockHeight)
                                .clickable { onCourseBlockClicked(mergedBlock) }
                        ) {
                            CourseBlock(
                                mergedBlock = mergedBlock
                            )
                        }
                    }
                }
            }
        }
    }
}


/**
 * 辅助函数：将 "HH:MM" 格式的时间字符串转换为从午夜开始的总分钟数。
 */
private fun timeToMinutes(time: String): Int {
    return try {
        val parts = time.split(":")
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        hour * 60 + minute
    } catch (e: Exception) {
        // 时间格式错误或解析失败时，返回 0
        0
    }
}

/**
 * 计算标准节次课程块的布局参数 (固定高度)。
 */
private fun calculateFixedLayout(
    mergedBlock: MergedCourseBlock,
    sectionHeightPx: Float // 单节课的像素高度
): Pair<Float, Float> {
    // 偏移量：从第一节课开始 (startSection - 1)
    val offsetY = (mergedBlock.startSection - 1) * sectionHeightPx

    // 高度：占据的节次数 * 单节课的像素高度
    val sectionsCount = mergedBlock.endSection - mergedBlock.startSection + 1
    val height = sectionsCount * sectionHeightPx

    return Pair(offsetY, height)
}

/**
 * 计算自定义时间课程块的布局参数 (比例计算)。
 */
private fun calculateProportionalLayout(
    mergedBlock: MergedCourseBlock,
    timeSlots: List<TimeSlot>,
    sectionHeightPx: Float // 单节课的像素高度 (例如 140.0 Px)
): Pair<Float, Float> {
    val timeSlotMap = timeSlots.associateBy { it.number }

    val course = mergedBlock.courses.firstOrNull()?.course ?: return calculateFixedLayout(mergedBlock, sectionHeightPx)

    val customStartTimeStr = course.customStartTime
    val customEndTimeStr = course.customEndTime
    if (customStartTimeStr == null || customEndTimeStr == null) {
        return calculateFixedLayout(mergedBlock, sectionHeightPx)
    }

    val customStartMinutes = timeToMinutes(customStartTimeStr)
    val customEndMinutes = timeToMinutes(customEndTimeStr)

    val S_real = timeSlots.firstOrNull { slot ->
        timeToMinutes(slot.endTime) > customStartMinutes
    }?.number ?: 1

    val E_real = timeSlots.firstOrNull { slot ->
        timeToMinutes(slot.endTime) >= customEndMinutes
    }?.number ?: timeSlots.size.coerceAtLeast(1)

    val realStartSlot = timeSlotMap[S_real]
    val realEndSlot = timeSlotMap[E_real]

    if (realStartSlot == null || realEndSlot == null || customStartMinutes >= customEndMinutes || S_real > E_real) {
        return calculateFixedLayout(mergedBlock, sectionHeightPx)
    }

    val totalSections = E_real - S_real + 1
    val totalSpanHeightPx = totalSections * sectionHeightPx

    val spanStartMinutes = timeToMinutes(realStartSlot.startTime)
    val spanEndMinutes = timeToMinutes(realEndSlot.endTime)
    val totalSpanDurationMinutes = spanEndMinutes - spanStartMinutes

    if (totalSpanDurationMinutes <= 0) {
        return calculateFixedLayout(mergedBlock, sectionHeightPx)
    }
    val pxPerMinute = totalSpanHeightPx / totalSpanDurationMinutes.toFloat()

    val fixedOffsetY = (S_real - 1) * sectionHeightPx
    val totalDurationMinutes = customEndMinutes - customStartMinutes

    val minuteOffsetInSpan = customStartMinutes - spanStartMinutes

    var finalOffsetY = fixedOffsetY + (minuteOffsetInSpan * pxPerMinute)
    var finalHeight = totalDurationMinutes.coerceAtLeast(0) * pxPerMinute

    val spanTopY = fixedOffsetY
    if (finalOffsetY < spanTopY) {
        val overflowHeight = spanTopY - finalOffsetY

        finalOffsetY = spanTopY
        finalHeight -= overflowHeight
    }
    val spanBottomY = fixedOffsetY + totalSpanHeightPx
    val courseBottomY = finalOffsetY + finalHeight

    if (courseBottomY > spanBottomY) {
        val overflowHeight = courseBottomY - spanBottomY

        finalHeight -= overflowHeight
    }
    if (finalHeight <= 0f) {
        finalHeight = sectionHeightPx
        finalOffsetY = fixedOffsetY
    }
    else if (finalHeight < sectionHeightPx) {
        finalHeight = sectionHeightPx
    }

    return Pair(finalOffsetY, finalHeight)
}


/**
 * 星期栏 UI 组件。
 */
@Composable
private fun DayHeader(
    displayDays: List<String>,
    dates: List<String>,
    cellWidth: Dp,
    timeColumnWidth: Dp,
    dayHeaderHeight: Dp,
    todayIndex: Int,
    gridLineColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dayHeaderHeight)
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                drawLine(
                    color = gridLineColor.copy(alpha = 0.3f),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f
                )
            }
    ) {
        Spacer(
            modifier = Modifier
                .width(timeColumnWidth)
                .fillMaxHeight()
        )

        // 星期几和日期
        displayDays.forEachIndexed { index, day ->
            val isToday = index == todayIndex
            val backgroundColor = if (isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            val textColor = if (isToday) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            val dateColor = if (isToday) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

            Column(
                modifier = Modifier
                    .width(cellWidth)
                    .fillMaxHeight()
                    .background(backgroundColor)
                    .drawBehind {
                        if (index < displayDays.size - 1) {
                            drawLine(
                                color = gridLineColor.copy(alpha = 0.3f),
                                start = Offset(size.width, 0f),
                                end = Offset(size.width, size.height),
                                strokeWidth = 1f
                            )
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = day, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textColor, lineHeight = 14.sp)
                if (dates.size > index) {
                    Text(text = dates[index], fontSize = 10.sp, color = dateColor, lineHeight = 10.sp)
                }
            }
        }
    }
}

/**
 * 左侧时间列表 UI 组件。
 */
@Composable
private fun TimeColumn(
    timeSlots: List<TimeSlot>,
    timeColumnWidth: Dp,
    sectionHeight: Dp,
    onTimeSlotClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gridLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

    Column(
        modifier = modifier
            .width(timeColumnWidth)
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                val strokeWidth = 1f
                val transparentColor = gridLineColor.copy(alpha = 0.3f)

                // 绘制右侧的垂直分割线
                drawLine(
                    color = transparentColor,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = strokeWidth
                )

                // 绘制所有横向分割线
                for (i in 0..timeSlots.size) {
                    val startY = i * sectionHeight.toPx()
                    drawLine(
                        color = transparentColor,
                        start = Offset(0f, startY),
                        end = Offset(size.width, startY),
                        strokeWidth = strokeWidth
                    )
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        timeSlots.forEach { slot ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sectionHeight)
                    .clickable {
                        onTimeSlotClicked()
                    }
                    .padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = slot.number.toString(), fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp)
                Text(text = slot.startTime, fontSize = 10.sp, lineHeight = 10.sp)
                Text(text = slot.endTime, fontSize = 10.sp, lineHeight = 10.sp)
            }
        }
    }
}

/**
 * 网格线 UI 组件。
 */
@Composable
private fun GridLines(
    dayCount: Int,
    timeSlotsCount: Int,
    cellWidth: Dp,
    gridHeight: Dp,
    sectionHeight: Dp,
    gridLineColor: Color
) {
    val strokeWidth = 1f
    val transparentColor = gridLineColor.copy(alpha = 0.3f)

    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        // 绘制竖线
        for (i in 1..dayCount) {
            val startX = i * cellWidth.toPx()
            drawLine(
                color = transparentColor,
                start = Offset(startX, 0f),
                end = Offset(startX, gridHeight.toPx()),
                strokeWidth = strokeWidth
            )
        }

        // 绘制横线
        for (i in 0..timeSlotsCount) {
            val startY = i * sectionHeight.toPx()
            drawLine(
                color = transparentColor,
                start = Offset(0f, startY),
                end = Offset(size.width, startY),
                strokeWidth = strokeWidth
            )
        }
    }
}

/**
 * 可点击的空白区域。
 */
@Composable
private fun ClickableGrid(
    dayCount: Int,
    timeSlotsCount: Int,
    sectionHeight: Dp,
    onGridCellClicked: (Int, Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        for (section in 1..timeSlotsCount) {
            Row(modifier = Modifier
                .fillMaxWidth()
                .height(sectionHeight)) {
                for (displayIndex in 0 until dayCount) {
                    Spacer(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable {
                                onGridCellClicked(displayIndex, section)
                            }
                    )
                }
            }
        }
    }
}


/**
 * 根据起始日重新排列星期列表。
 * @param originalDays 原始星期列表 (周一, 周二, ..., 周日)
 * @param firstDayOfWeek 一周的起始日 (1=周一, 7=周日)
 * @return 排列后的星期列表
 */
private fun rearrangeDays(originalDays: List<String>, firstDayOfWeek: Int): List<String> {
    val startIndex = firstDayOfWeek - 1 // 转换为 0-based 索引 (0=周一, 6=周日)
    if (startIndex == 0) return originalDays

    // 将列表分成两部分并交换位置
    val part1 = originalDays.subList(startIndex, originalDays.size)
    val part2 = originalDays.subList(0, startIndex)
    return part1 + part2
}

/**
 * 将课程的 Day (1-7) 映射到当前网格的显示索引 (0-N)。
 * @param courseDay 课程的星期几 (1=周一, 7=周日)
 * @param firstDayOfWeek 一周的起始日 (1=周一, 7=周日)
 * @param showWeekends 是否显示周末
 * @return 在显示列表中的 0-based 索引，如果不在显示范围内则返回 -1
 */
private fun mapDayToDisplayIndex(courseDay: Int, firstDayOfWeek: Int, showWeekends: Boolean): Int {
    val totalDays = if (showWeekends) 7 else 5

    // 计算 0-based 索引: (课程日 - 起始日 + 7) % 7
    val theoreticalIndex = (courseDay - firstDayOfWeek + 7) % 7

    // 检查是否在显示的列数范围内
    if (theoreticalIndex >= totalDays) {
        return -1
    }

    return theoreticalIndex
}

/**
 * 将网格的显示索引 (0-N) 映射回课程 DayOfWeek (1-7)。
 */
private fun mapDisplayIndexToDay(displayIndex: Int, firstDayOfWeek: Int): Int {
    // 0-based 索引: (起始日 0-based + 显示索引) % 7
    // 1-based DayOfWeek: + 1
    val day = (firstDayOfWeek - 1 + displayIndex) % 7 + 1
    return day
}