package com.xingheyuzhuan.shiguangschedule.ui.schedule.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.ScheduleModeProto
import com.xingheyuzhuan.shiguangschedule.ui.schedule.MergedCourseBlock
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
@Composable
fun ScheduleGrid(
    gridScrollState: ScrollState,
    style: ScheduleGridStyleComposed,
    dates: List<String>,
    currentYear: String,
    timeSlots: List<TimeSlot>,
    mergedCourses: List<MergedCourseBlock>,
    showWeekends: Boolean,
    todayIndex: Int,
    firstDayOfWeek: Int,
    currentSectionIndex: Int = -1,
    onCourseBlockClicked: (MergedCourseBlock) -> Unit,
    onGridCellClicked: (Int, Int) -> Unit,
    onTimeSlotClicked: () -> Unit,
    onHoldStateChanged: (isHolding: Boolean) -> Unit = {},
    onCourseMovedWithinGrid: (block: MergedCourseBlock, newDay: Int, newStartSection: Float, newEndSection: Float) -> Unit = { _, _, _, _ -> },
    onCourseTimeAdjusted: (block: MergedCourseBlock, newStart: Float, newEnd: Float) -> Unit = { _, _, _ -> },
    onInitiateFloatingMode: (block: MergedCourseBlock) -> Unit = {}
) {
    Box(Modifier.fillMaxSize()) {
        val density = LocalDensity.current

        var expandedItem by remember(mergedCourses) { mutableStateOf<ISingleSchedulable?>(null) }

        var activeMoveIntent by remember(expandedItem) { mutableStateOf<CourseMoveIntent?>(null) }
        var bodyDragOffsetX by remember(expandedItem) { mutableStateOf(0f) }
        var bodyDragOffsetY by remember(expandedItem) { mutableStateOf(0f) }

        var isTopHandleDragging by remember(expandedItem) { mutableStateOf(false) }
        var isBottomHandleDragging by remember(expandedItem) { mutableStateOf(false) }

        var topHandleDragOffsetY by remember(expandedItem) { mutableStateOf(0f) }
        var bottomHandleDragOffsetY by remember(expandedItem) { mutableStateOf(0f) }

        var gridWidthPx by remember { mutableStateOf(0f) }
        var viewportHeightPx by remember { mutableStateOf(0f) }

        LaunchedEffect(mergedCourses) {
            expandedItem = null
            activeMoveIntent = null
            isTopHandleDragging = false
            isBottomHandleDragging = false
            topHandleDragOffsetY = 0f
            bottomHandleDragOffsetY = 0f
            bodyDragOffsetX = 0f
            bodyDragOffsetY = 0f
            onHoldStateChanged(false)
        }

        val pageTextColor = style.pageTextColor ?: MaterialTheme.colorScheme.onSurface
        val pageSubTextColor = pageTextColor.copy(alpha = 0.7f)
        val weekDays = stringArrayResource(R.array.week_days_short_names).toList()
        val reorderedWeekDays = rearrangeDays(weekDays, firstDayOfWeek)
        val displayDays = if (showWeekends) reorderedWeekDays else reorderedWeekDays.take(5)

        val displayDaysCount = displayDays.size
        val is24HourMode = style.scheduleMode == ScheduleModeProto.TIME_24H_MODE
        val maxGridSections = if (is24HourMode) 24 else timeSlots.size

        val totalGridHeight = style.sectionHeight * maxGridSections
        val gridLineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        val strokeWidthPx = 1f

        val singleSchedulables = remember(mergedCourses, firstDayOfWeek, showWeekends) {
            calculateSingleSchedulables(mergedCourses, firstDayOfWeek, showWeekends)
        }
        val sectionHeightPx = with(density) { style.sectionHeight.toPx() }

        var activeDragHour by remember { mutableStateOf<Int?>(null) }
        var activeDragMinuteStr by remember { mutableStateOf<String?>(null) }

        if (is24HourMode && expandedItem != null) {
            val currentTargetSection = when {
                isTopHandleDragging -> {
                    val minGap = 0.25f
                    val deltaSection = topHandleDragOffsetY / sectionHeightPx
                    var proposedStart = expandedItem!!.startSection + deltaSection
                    proposedStart = (proposedStart / 0.25f).roundToInt() * 0.25f
                    proposedStart.coerceIn(0f, expandedItem!!.endSection - minGap)
                }
                isBottomHandleDragging -> {
                    val minGap = 0.25f
                    val deltaSection = bottomHandleDragOffsetY / sectionHeightPx
                    var proposedEnd = expandedItem!!.endSection + deltaSection
                    proposedEnd = (proposedEnd / 0.25f).roundToInt() * 0.25f
                    proposedEnd.coerceIn(expandedItem!!.startSection + minGap, maxGridSections.toFloat())
                }
                activeMoveIntent != null -> {
                    val duration = activeMoveIntent!!.duration
                    var targetStart = activeMoveIntent!!.initialStartSection + (bodyDragOffsetY / sectionHeightPx)
                    targetStart = (targetStart / 0.25f).roundToInt() * 0.25f
                    targetStart.coerceIn(0f, maxGridSections - duration)
                }
                else -> null
            }

            if (currentTargetSection != null) {
                val hour = kotlin.math.floor(currentTargetSection).toInt().coerceIn(0, maxGridSections - 1)
                val minuteFraction = currentTargetSection - kotlin.math.floor(currentTargetSection)
                val minute = (minuteFraction * 60).roundToInt() % 60

                activeDragHour = hour
                activeDragMinuteStr = String.format(Locale.US, "%02d", minute)
            } else {
                activeDragHour = null
                activeDragMinuteStr = null
            }
        } else {
            activeDragHour = null
            activeDragMinuteStr = null
        }

        // 自动边缘滚动监测线程
        LaunchedEffect(expandedItem, activeMoveIntent != null, isTopHandleDragging, isBottomHandleDragging) {
            if (expandedItem != null && (activeMoveIntent != null || isTopHandleDragging || isBottomHandleDragging)) {
                while (true) {
                    val item = expandedItem ?: break
                    val threshold = with(density) { 40.dp.toPx() }
                    val scrollSpeed = with(density) { 8.dp.toPx() }

                    var relativeTop: Float? = null
                    var relativeBottom: Float? = null

                    if (activeMoveIntent != null) {
                        val currentTopInGrid = item.startSection * sectionHeightPx + bodyDragOffsetY
                        val currentBottomInGrid = item.endSection * sectionHeightPx + bodyDragOffsetY
                        relativeTop = currentTopInGrid - gridScrollState.value
                        relativeBottom = currentBottomInGrid - gridScrollState.value
                    } else if (isTopHandleDragging) {
                        val currentTopInGrid = item.startSection * sectionHeightPx + topHandleDragOffsetY
                        relativeTop = currentTopInGrid - gridScrollState.value
                    } else if (isBottomHandleDragging) {
                        val currentBottomInGrid = item.endSection * sectionHeightPx + bottomHandleDragOffsetY
                        relativeBottom = currentBottomInGrid - gridScrollState.value
                    }

                    var scrollAmount = 0f
                    if (relativeTop != null && relativeTop < threshold) {
                        if (gridScrollState.value > 0) {
                            scrollAmount = -scrollSpeed
                            if (gridScrollState.value + scrollAmount < 0) scrollAmount = -gridScrollState.value.toFloat()
                        }
                    } else if (relativeBottom != null && viewportHeightPx > 0f && relativeBottom > viewportHeightPx - threshold) {
                        val maxScroll = gridScrollState.maxValue
                        if (gridScrollState.value < maxScroll) {
                            scrollAmount = scrollSpeed
                            if (gridScrollState.value + scrollAmount > maxScroll) scrollAmount = (maxScroll - gridScrollState.value).toFloat()
                        }
                    }

                    if (scrollAmount != 0f) {
                        gridScrollState.scrollBy(scrollAmount)
                        if (activeMoveIntent != null) {
                            bodyDragOffsetY += scrollAmount
                        } else if (isTopHandleDragging) {
                            topHandleDragOffsetY += scrollAmount
                        } else if (isBottomHandleDragging) {
                            bottomHandleDragOffsetY += scrollAmount
                        }
                    }
                    delay(16.milliseconds)
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            DayHeader(style, displayDays, dates, currentYear, todayIndex, gridLineColor, pageTextColor, pageSubTextColor, strokeWidthPx)

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportHeightPx = it.height.toFloat() }
                    .verticalScroll(state = gridScrollState, enabled = expandedItem == null)
            ) {
                TimeColumn(
                    style = style, timeSlots = timeSlots, maxGridSections = maxGridSections,
                    is24HourMode = is24HourMode, onTimeSlotClicked = onTimeSlotClicked,
                    modifier = Modifier.height(totalGridHeight), lineColor = gridLineColor,
                    currentSectionIndex = currentSectionIndex, textColor = pageTextColor,
                    subTextColor = pageSubTextColor, strokeWidthPx = strokeWidthPx,
                    activeDragHour = activeDragHour,
                    activeDragMinuteStr = activeDragMinuteStr
                )

                Layout(
                    content = {
                        singleSchedulables.forEach { item ->
                            val isExpanded = expandedItem != null && expandedItem?.parentBlock === item.parentBlock

                            Box(
                                modifier = Modifier
                                    .padding(style.courseBlockOuterPadding)
                                    .zIndex(if (isExpanded) 2f else 0f)
                                    .then(
                                        if (!isExpanded) {
                                            Modifier.pointerInput(item) {
                                                detectTapGestures(
                                                    onTap = { onCourseBlockClicked(item.parentBlock) },
                                                    onLongPress = {
                                                        expandedItem = item
                                                        onHoldStateChanged(true)
                                                    }
                                                )
                                            }
                                        } else {
                                            Modifier.pointerInput(item) {
                                                detectTapGestures(onTap = { expandedItem = null; onHoldStateChanged(false) })
                                            }
                                        }
                                    )
                            ) {
                                CourseBlock(
                                    courseWrapper = item.courseWrapper,
                                    isVisualDemoted = item.parentBlock.isVisualDemoted,
                                    style = style,
                                    timeSlots = timeSlots,
                                    isFloating = isExpanded,
                                    modifier = if (isExpanded) {
                                        if (!item.parentBlock.isVisualDemoted) {
                                            Modifier.pointerInput(item, gridWidthPx) {
                                                detectDragGestures(
                                                    onDragStart = {
                                                        activeMoveIntent = CourseMoveIntent(item.parentBlock, item.parentBlock.day, item.startSection, item.endSection - item.startSection)
                                                        bodyDragOffsetX = 0f; bodyDragOffsetY = 0f
                                                    },
                                                    onDragEnd = {
                                                        val intent = activeMoveIntent
                                                        if (intent != null && gridWidthPx > 0f) {
                                                            val cellWidth = gridWidthPx / displayDaysCount

                                                            val initialX = item.columnIndex * cellWidth + (item.subColumnIndex * (cellWidth / item.subColumnCount))

                                                            val currentAbsoluteX = initialX + bodyDragOffsetX
                                                            val blockWidth = cellWidth / item.subColumnCount

                                                            val strictThresholdPx = with(density) { (-6.18).dp.toPx() }

                                                            val touchLeftEdge = currentAbsoluteX <= strictThresholdPx
                                                            val touchRightEdge = (currentAbsoluteX + blockWidth) >= (gridWidthPx - strictThresholdPx)

                                                            if (touchLeftEdge || touchRightEdge) {
                                                                onInitiateFloatingMode(intent.parentBlock)
                                                                expandedItem = null
                                                                activeMoveIntent = null
                                                                onHoldStateChanged(false)
                                                            } else {
                                                                // 否则，正常走原本同周调课换算
                                                                val deltaCols = (bodyDragOffsetX / cellWidth).roundToInt()
                                                                val targetDisplayIdx = (item.columnIndex + deltaCols).coerceIn(0, displayDaysCount - 1)
                                                                val targetDay = mapDisplayIndexToDay(targetDisplayIdx, firstDayOfWeek)
                                                                var targetStart = intent.initialStartSection + (bodyDragOffsetY / sectionHeightPx)
                                                                targetStart = if (is24HourMode) (targetStart / 0.25f).roundToInt() * 0.25f else targetStart.roundToInt().toFloat()
                                                                targetStart = targetStart.coerceIn(0f, maxGridSections - intent.duration)
                                                                val targetEnd = targetStart + intent.duration

                                                                val targetX = targetDisplayIdx * cellWidth
                                                                bodyDragOffsetX = targetX - initialX
                                                                bodyDragOffsetY = (targetStart - intent.initialStartSection) * sectionHeightPx

                                                                onCourseMovedWithinGrid(intent.parentBlock, targetDay, targetStart, targetEnd)
                                                            }
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        activeMoveIntent = null; expandedItem = null; onHoldStateChanged(false)
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        bodyDragOffsetX += dragAmount.x
                                                        bodyDragOffsetY += dragAmount.y
                                                    }
                                                )
                                            }
                                        } else {
                                            Modifier
                                        }
                                    } else Modifier
                                )

                                if (isExpanded && activeMoveIntent == null && !item.parentBlock.isVisualDemoted) {
                                    CourseEditHandles(
                                        onDragStart = { isTop ->
                                            if (isTop) {
                                                isTopHandleDragging = true
                                                isBottomHandleDragging = false
                                                topHandleDragOffsetY = 0f
                                            } else {
                                                isTopHandleDragging = false
                                                isBottomHandleDragging = true
                                                bottomHandleDragOffsetY = 0f
                                            }
                                        },
                                        onDragging = { deltaY ->
                                            if (isTopHandleDragging) {
                                                val minGap = if (is24HourMode) 0.25f else 1f
                                                val maxAllowedDragY = ((item.endSection - item.startSection) - minGap) * sectionHeightPx

                                                val tentativeOffsetY = topHandleDragOffsetY + deltaY
                                                if (is24HourMode) {
                                                    val proposedStart = item.startSection + (tentativeOffsetY / sectionHeightPx)
                                                    topHandleDragOffsetY = if (proposedStart > item.endSection - minGap) {
                                                        maxAllowedDragY
                                                    } else {
                                                        tentativeOffsetY
                                                    }
                                                } else {
                                                    topHandleDragOffsetY = tentativeOffsetY.coerceAtMost(maxAllowedDragY)
                                                }
                                            } else if (isBottomHandleDragging) {
                                                val minGap = if (is24HourMode) 0.25f else 1f
                                                val maxAllowedDragUpY = -(((item.endSection - item.startSection) - minGap) * sectionHeightPx)

                                                val tentativeOffsetY = bottomHandleDragOffsetY + deltaY
                                                if (is24HourMode) {
                                                    val proposedEnd = item.endSection + (tentativeOffsetY / sectionHeightPx)
                                                    bottomHandleDragOffsetY = if (proposedEnd < item.startSection + minGap) {
                                                        maxAllowedDragUpY
                                                    } else {
                                                        tentativeOffsetY
                                                    }
                                                } else {
                                                    bottomHandleDragOffsetY = tentativeOffsetY.coerceAtLeast(maxAllowedDragUpY)
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            val currentItem = expandedItem
                                            if (currentItem != null) {
                                                val minGap = if (is24HourMode) 0.25f else 1f
                                                var finalStart = currentItem.startSection
                                                var finalEnd = currentItem.endSection

                                                if (isTopHandleDragging) {
                                                    val deltaSection = topHandleDragOffsetY / sectionHeightPx
                                                    var proposedStart = currentItem.startSection + deltaSection
                                                    proposedStart = if (is24HourMode) (proposedStart / 0.25f).roundToInt() * 0.25f else proposedStart.roundToInt().toFloat()
                                                    finalStart = proposedStart.coerceIn(0f, finalEnd - minGap)
                                                    topHandleDragOffsetY = (finalStart - currentItem.startSection) * sectionHeightPx
                                                } else if (isBottomHandleDragging) {
                                                    val deltaSection = bottomHandleDragOffsetY / sectionHeightPx
                                                    var proposedEnd = currentItem.endSection + deltaSection
                                                    proposedEnd = if (is24HourMode) (proposedEnd / 0.25f).roundToInt() * 0.25f else proposedEnd.roundToInt().toFloat()
                                                    finalEnd = proposedEnd.coerceIn(finalStart + minGap, maxGridSections.toFloat())
                                                    bottomHandleDragOffsetY = (finalEnd - currentItem.endSection) * sectionHeightPx
                                                }
                                                if (finalStart != currentItem.startSection || finalEnd != currentItem.endSection) {
                                                    onCourseTimeAdjusted(currentItem.parentBlock, finalStart, finalEnd)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .height(totalGridHeight).weight(1f)
                        .onSizeChanged { gridWidthPx = it.width.toFloat() }
                        .drawBehind {
                            if (style.hideGridLines) return@drawBehind
                            val cellWidth = size.width / displayDaysCount
                            for (i in 1..displayDaysCount) {
                                val x = i * cellWidth
                                drawLine(gridLineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = strokeWidthPx)
                            }
                            for (i in 1..maxGridSections) {
                                val y = i * sectionHeightPx
                                drawLine(gridLineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = strokeWidthPx)
                            }
                        }
                        .pointerInput(displayDaysCount, sectionHeightPx, firstDayOfWeek, maxGridSections, is24HourMode, expandedItem) {
                            detectTapGestures { offset ->
                                if (expandedItem != null) {
                                    expandedItem = null; onHoldStateChanged(false)
                                    return@detectTapGestures
                                }
                                val dayIdx = (offset.x / (size.width / displayDaysCount)).toInt().coerceIn(0, displayDaysCount - 1)
                                val secIdx = (offset.y / sectionHeightPx).toInt().coerceIn(0, maxGridSections - 1)
                                onGridCellClicked(mapDisplayIndexToDay(dayIdx, firstDayOfWeek), if (is24HourMode) secIdx else (secIdx + 1))
                            }
                        }
                ) { measurables, constraints ->
                    val cellWidth = constraints.maxWidth / displayDaysCount
                    val minGapPx = if (is24HourMode) 0f else with(density) { 30.dp.toPx() }

                    val placeables = measurables.mapIndexed { index, measurable ->
                        val item = singleSchedulables[index]
                        val isExpanded = expandedItem != null && expandedItem?.parentBlock === item.parentBlock

                        val originalHeightPx = ((item.endSection - item.startSection) * sectionHeightPx).toInt()

                        var calculatedHeightPx = originalHeightPx
                        if (isExpanded) {
                            if (isTopHandleDragging || topHandleDragOffsetY != 0f) {
                                calculatedHeightPx = (originalHeightPx - topHandleDragOffsetY.roundToInt()).coerceAtLeast(minGapPx.toInt())
                            } else if (isBottomHandleDragging || bottomHandleDragOffsetY != 0f) {
                                calculatedHeightPx = (originalHeightPx + bottomHandleDragOffsetY.roundToInt()).coerceAtLeast(minGapPx.toInt())
                            }
                        }

                        measurable.measure(
                            Constraints.fixed(
                                (cellWidth / if (isExpanded) 1 else item.subColumnCount),
                                calculatedHeightPx
                            )
                        )
                    }

                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeables.forEachIndexed { index, placeable ->
                            val item = singleSchedulables[index]
                            val isExpanded = expandedItem != null && expandedItem?.parentBlock === item.parentBlock
                            val isMoving = activeMoveIntent != null && activeMoveIntent?.parentBlock === item.parentBlock

                            val originalX = item.columnIndex * cellWidth + (if (isExpanded) 0 else item.subColumnIndex * (cellWidth / item.subColumnCount))
                            val originalY = (item.startSection * sectionHeightPx).toInt()

                            var xPosition = originalX
                            var yPosition = originalY

                            if (isMoving) {
                                val intent = activeMoveIntent
                                if (intent != null) {
                                    val intentOriginalX = item.columnIndex * cellWidth
                                    val intentOriginalY = (intent.initialStartSection * sectionHeightPx).toInt()
                                    xPosition = (intentOriginalX + bodyDragOffsetX).toInt()
                                    yPosition = (intentOriginalY + bodyDragOffsetY).toInt()
                                }
                            } else if (isExpanded) {
                                if (isTopHandleDragging || topHandleDragOffsetY != 0f) {
                                    val originalHeightPx = ((item.endSection - item.startSection) * sectionHeightPx).toInt()
                                    yPosition = if (originalHeightPx - topHandleDragOffsetY >= minGapPx) {
                                        (originalY + topHandleDragOffsetY).toInt()
                                    } else {
                                        (originalY + (originalHeightPx - minGapPx)).toInt()
                                    }
                                }
                            }

                            placeable.placeRelative(xPosition, yPosition)
                        }
                    }
                }
            }
        }
    }
}