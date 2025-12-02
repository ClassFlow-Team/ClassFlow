package com.xingheyuzhuan.shiguangschedule.ui.settings.course

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.repository.DualColor
import kotlinx.coroutines.launch

@Composable
fun WeekSelector(
    selectedWeeks: Set<Int>,
    onWeekClick: () -> Unit
) {
    val labelCourseWeeks = stringResource(R.string.label_course_weeks)
    val buttonSelectWeeks = stringResource(R.string.button_select_weeks)
    val textWeeksSelected = stringResource(R.string.text_weeks_selected)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = labelCourseWeeks, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onWeekClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            val weeksText = if (selectedWeeks.isEmpty()) {
                buttonSelectWeeks
            } else {
                String.format(textWeeksSelected, selectedWeeks.sorted().joinToString(", "))
            }
            Text(text = weeksText, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WeekSelectorBottomSheet(
    totalWeeks: Int,
    selectedWeeks: Set<Int>,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<Int>) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val modalBottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tempSelectedWeeks by remember { mutableStateOf(selectedWeeks) }

    val titleSelectWeeks = stringResource(R.string.title_select_weeks)
    val actionSelectAll = stringResource(R.string.action_select_all)
    val actionSingleWeek = stringResource(R.string.action_single_week)
    val actionDoubleWeek = stringResource(R.string.action_double_week)
    val actionCancel = stringResource(R.string.action_cancel)
    val actionConfirm = stringResource(R.string.action_confirm)

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
                text = titleSelectWeeks,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 48.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(totalWeeks) { week ->
                    val weekNumber = week + 1
                    val isSelected = tempSelectedWeeks.contains(weekNumber)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                tempSelectedWeeks = if (isSelected) {
                                    tempSelectedWeeks - weekNumber
                                } else {
                                    tempSelectedWeeks + weekNumber
                                }
                            }
                            .then(
                                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = weekNumber.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InputChip(
                    selected = tempSelectedWeeks.size == totalWeeks,
                    onClick = {
                        tempSelectedWeeks = if (tempSelectedWeeks.size == totalWeeks) {
                            emptySet()
                        } else {
                            (1..totalWeeks).toSet()
                        }
                    },
                    label = { Text(actionSelectAll) }
                )
                InputChip(
                    selected = tempSelectedWeeks.all { it % 2 != 0 } && tempSelectedWeeks.isNotEmpty(),
                    onClick = {
                        tempSelectedWeeks = (1..totalWeeks).filter { it % 2 != 0 }.toSet()
                    },
                    label = { Text(actionSingleWeek) }
                )
                InputChip(
                    selected = tempSelectedWeeks.all { it % 2 == 0 } && tempSelectedWeeks.isNotEmpty(),
                    onClick = {
                        tempSelectedWeeks = (1..totalWeeks).filter { it % 2 == 0 }.toSet()
                    },
                    label = { Text(actionDoubleWeek) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(actionCancel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = {
                        onConfirm(tempSelectedWeeks)
                        coroutineScope.launch { modalBottomSheetState.hide() }.invokeOnCompletion {
                            if (!modalBottomSheetState.isVisible) {
                                onDismissRequest()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(actionConfirm, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
fun ColorPicker(
    selectedColor: Color,
    onColorClick: () -> Unit
) {
    val labelCourseColor = stringResource(R.string.label_course_color)
    val buttonSelectColor = stringResource(R.string.button_select_color)
    val textColor = if (selectedColor.luminance() > 0.5f) Color.Black else Color.White

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = labelCourseColor, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onColorClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = selectedColor)
        ) {
            Text(buttonSelectColor, color = textColor)
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerBottomSheet(
    colorMaps: List<DualColor>,
    selectedIndex: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val modalBottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var tempSelectedIndex by remember { mutableStateOf(selectedIndex) }

    val titleSelectColor = stringResource(R.string.title_select_color)
    val actionCancel = stringResource(R.string.action_cancel)
    val actionConfirm = stringResource(R.string.action_confirm)

    val isDarkTheme = isSystemInDarkTheme()

    val displayColors = remember(isDarkTheme, colorMaps) {
        colorMaps.map { dualColor ->
            if (isDarkTheme) dualColor.dark else dualColor.light
        }
    }

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
                text = titleSelectColor,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(displayColors) { index, color ->
                    val isSelected = tempSelectedIndex == index
                    val ringWidth = 3.dp

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable {
                                tempSelectedIndex = index
                            }
                            .then(
                                if (isSelected) Modifier.border(
                                    width = ringWidth,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape
                                ) else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(if (isSelected) ringWidth else 0.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(actionCancel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = {
                        onConfirm(tempSelectedIndex)
                        coroutineScope.launch { modalBottomSheetState.hide() }.invokeOnCompletion {
                            if (!modalBottomSheetState.isVisible) {
                                onDismissRequest()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(actionConfirm, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}