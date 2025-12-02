package com.xingheyuzhuan.shiguangschedule.ui.settings.course

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import com.xingheyuzhuan.shiguangschedule.ui.components.NativeNumberPicker

/**
 * 节次时间选择底部弹窗
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseTimePickerBottomSheet(
    selectedDay: Int,
    onDaySelected: (Int) -> Unit,
    startSection: Int,
    onStartSectionChange: (Int) -> Unit,
    endSection: Int,
    onEndSectionChange: (Int) -> Unit,
    timeSlots: List<TimeSlot>,
    onDismissRequest: () -> Unit
) {
    val modalBottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var tempSelectedDay by remember { mutableStateOf(selectedDay) }
    var tempStartSection by remember { mutableStateOf(startSection) }
    var tempEndSection by remember { mutableStateOf(endSection) }

    val context = LocalContext.current
    val timeInvalidText = stringResource(R.string.toast_time_invalid)
    val confirmText = stringResource(R.string.action_confirm)
    val labelDayOfWeek = stringResource(R.string.label_day_of_week)
    val labelStartSection = stringResource(R.string.label_start_section)
    val labelEndSection = stringResource(R.string.label_end_section)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = modalBottomSheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.title_select_time),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // 三列布局：星期 | 开始节次 | 结束节次 (纯节次模式)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 第 1 列：星期选择器
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = labelDayOfWeek, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    DayPicker(
                        selectedDay = tempSelectedDay,
                        onDaySelected = { newDay -> tempSelectedDay = newDay }
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))

                // 第 2 列：开始节次选择器
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = labelStartSection, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionPicker(
                        selectedSection = tempStartSection,
                        onSectionSelected = { newStart ->
                            tempStartSection = newStart
                            // 保证结束节次不小于开始节次
                            if (newStart > tempEndSection) {
                                tempEndSection = newStart
                            }
                        },
                        timeSlots = timeSlots
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))

                // 第 3 列：结束节次选择器
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = labelEndSection, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    SectionPicker(
                        selectedSection = tempEndSection,
                        onSectionSelected = { newEnd ->
                            tempEndSection = newEnd
                        },
                        timeSlots = timeSlots
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    // 节次模式下的验证
                    if (tempStartSection > tempEndSection) {
                        Toast.makeText(context, timeInvalidText, Toast.LENGTH_SHORT).show()
                    } else {
                        onDaySelected(tempSelectedDay)
                        onStartSectionChange(tempStartSection)
                        onEndSectionChange(tempEndSection)
                        onDismissRequest() // 关闭弹窗
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(confirmText, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}


/**
 * 自定义时间模式
 */
@Composable
fun DayPickerDialog(
    selectedDay: Int,
    onDismissRequest: () -> Unit,
    onDaySelected: (Int) -> Unit
) {
    val confirmText = stringResource(R.string.action_confirm)
    var tempSelectedDay by remember { mutableStateOf(selectedDay) }

    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.label_day_of_week),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            DayPicker(
                selectedDay = tempSelectedDay,
                onDaySelected = { newDay -> tempSelectedDay = newDay }
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    onDaySelected(tempSelectedDay)
                }) {
                    Text(confirmText)
                }
            }
        }
    }
}


/**
 * 封装 Material 3 TimePicker 的 Dialog。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialTime: String,
    onDismissRequest: () -> Unit,
    onTimeSelected: (String) -> Unit
) {
    val parts = initialTime.split(":")
    val initialHour = parts.getOrNull(0)?.toIntOrNull() ?: 8
    val initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0

    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )

    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TimePicker(state = timePickerState)
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        val newTime = String.format("%02d:%02d", timePickerState.hour, timePickerState.minute)
                        onTimeSelected(newTime)
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        }
    }
}


@Composable
fun DayPicker(
    selectedDay: Int,
    onDaySelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val days = stringArrayResource(R.array.week_days_full_names)
    val selectedDayName = days.getOrNull(selectedDay - 1) ?: days.first()

    NativeNumberPicker(
        values = days.toList(),
        selectedValue = selectedDayName,
        onValueChange = { dayName ->
            val dayNumber = days.indexOf(dayName) + 1
            onDaySelected(dayNumber)
        },
        modifier = modifier
    )
}

@Composable
fun SectionPicker(
    selectedSection: Int,
    onSectionSelected: (Int) -> Unit,
    timeSlots: List<TimeSlot>,
    modifier: Modifier = Modifier,
) {
    val sectionNumbers = timeSlots.map { it.number }.sorted()
    val validSelectedSection = if (selectedSection in sectionNumbers) selectedSection else sectionNumbers.firstOrNull() ?: 1

    NativeNumberPicker(
        values = sectionNumbers,
        selectedValue = validSelectedSection,
        onValueChange = onSectionSelected,
        modifier = modifier
    )
}