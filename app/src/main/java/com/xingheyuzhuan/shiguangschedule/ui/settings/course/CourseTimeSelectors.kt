package com.xingheyuzhuan.shiguangschedule.ui.settings.course

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot

/**
 * 封装了课程时间标题、切换开关和根据模式选择性渲染内容的 Composable。
 */
@Composable
fun TimeAreaSelector(
    day: Int,
    startSection: Int,
    endSection: Int,
    timeSlots: List<TimeSlot>,
    isCustomTime: Boolean,
    customStartTime: String,
    customEndTime: String,
    onIsCustomTimeChange: (Boolean) -> Unit,
    // Custom Time Click Handlers
    onDayClick: () -> Unit,
    onStartTimeClick: () -> Unit,
    onEndTimeClick: () -> Unit,
    // Section Mode Click Handler
    onSectionButtonClick: () -> Unit
) {
    val labelCourseTime = stringResource(R.string.label_course_time)
    val labelCustomTime = stringResource(R.string.label_custom_time)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 标题和切换开关
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = labelCourseTime, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = labelCustomTime,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isCustomTime,
                    onCheckedChange = onIsCustomTimeChange
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (isCustomTime) {
            // 模式 1: 自定义时间 (3 个并排按钮)
            CustomTimeButtons(
                day = day,
                customStartTime = customStartTime,
                customEndTime = customEndTime,
                onDayClick = onDayClick,
                onStartTimeClick = onStartTimeClick,
                onEndTimeClick = onEndTimeClick
            )
        } else {
            // 模式 2: 节次选择 (1 个按钮)
            SectionSelectorButton(
                day = day,
                startSection = startSection,
                endSection = endSection,
                timeSlots = timeSlots,
                onButtonClick = onSectionButtonClick
            )
        }
    }
}

/**
 * 自定义时间模式：主屏幕上的三个并排按钮。
 */
@Composable
private fun CustomTimeButtons(
    day: Int,
    customStartTime: String,
    customEndTime: String,
    onDayClick: () -> Unit,
    onStartTimeClick: () -> Unit,
    onEndTimeClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. 星期选择按钮
        DaySelectorButton(
            selectedDay = day,
            onClick = onDayClick,
            modifier = Modifier.weight(1f)
        )
        // 2. 开始时间按钮
        TimeSelectorButton(
            labelId = R.string.label_start_time,
            timeValue = customStartTime,
            onTimeClick = onStartTimeClick,
            modifier = Modifier.weight(1f)
        )
        // 3. 结束时间按钮
        TimeSelectorButton(
            labelId = R.string.label_end_time,
            timeValue = customEndTime,
            onTimeClick = onEndTimeClick,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 节次选择模式：主屏幕上的单个按钮，用于打开底部弹窗。
 */
@Composable
private fun SectionSelectorButton(
    day: Int,
    startSection: Int,
    endSection: Int,
    timeSlots: List<TimeSlot>,
    onButtonClick: () -> Unit
) {
    val days = stringArrayResource(R.array.week_days_full_names)
    val dayName = days.getOrNull(day - 1) ?: days.first()

    val sectionCount = if (endSection >= startSection) {
        stringResource(R.string.course_time_sections_count, endSection - startSection + 1)
    } else {
        ""
    }

    val sectionsText = if (timeSlots.isNotEmpty()) {
        "${startSection}-${endSection}${stringResource(R.string.label_section_range_suffix)}"
    } else {
        stringResource(R.string.label_none)
    }

    val timeInfo = "$sectionsText $sectionCount"
    val buttonText = "$dayName $timeInfo"

    Button(
        onClick = onButtonClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Text(text = buttonText, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}


/**
 * 主屏幕上的星期选择按钮。
 */
@Composable
private fun DaySelectorButton(
    selectedDay: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val days = stringArrayResource(R.array.week_days_full_names)
    val dayName = days.getOrNull(selectedDay - 1) ?: days.first()
    val labelSelectDay = stringResource(R.string.label_day_of_week)

    // ⭐ 修改点：将 OutlinedButton 替换为 Button，并应用实心背景色
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            // 使用与节次模式相同的实心背景色
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Text(
            text = dayName.ifBlank { labelSelectDay },
            // 设置文本颜色以配合背景色
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}


/**
 * 主屏幕上的自定义时间按钮 (无标题，显示作用)。
 */
@Composable
private fun TimeSelectorButton(
    @StringRes labelId: Int,
    timeValue: String,
    onTimeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val placeholderText = stringResource(labelId) // e.g., "开始时间"

    Button(
        onClick = onTimeClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Text(
            text = timeValue.ifBlank { placeholderText },
            // 设置文本颜色以配合背景色
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}