package com.xingheyuzhuan.shiguangschedule.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xingheyuzhuan.shiguangschedule.AppEntryPoint
import com.xingheyuzhuan.shiguangschedule.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTable
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CourseTablePickerDialog(
    title: String,
    onDismissRequest: () -> Unit,
    onTableSelected: (CourseTable) -> Unit,
) {
    val context = LocalContext.current
    val entryPoint = EntryPointAccessors.fromApplication(
        context.applicationContext,
        AppEntryPoint::class.java
    )
    val courseTableRepository = entryPoint.courseTableRepository()
    val appSettingsRepository = entryPoint.appSettingsRepository()

    val courseTables by courseTableRepository.getAllCourseTables().collectAsState(initial = emptyList())
    val appSettings by appSettingsRepository.getAppSettings().collectAsState(initial = null)

    var selectedTable by remember { mutableStateOf<CourseTable?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // 快速新建课表（上游同步：弹窗内 + 号按钮）
    var showAddTableDialog by remember { mutableStateOf(false) }
    var newTableName by remember { mutableStateOf("") }

    LaunchedEffect(courseTables, appSettings) {
        if (selectedTable == null && appSettings?.currentCourseTableId != null) {
            selectedTable = courseTables.find { it.id == appSettings?.currentCourseTableId }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column {
                if (courseTables.isEmpty()) {
                    Text(
                        text = stringResource(R.string.text_no_course_tables),
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(courseTables) { courseTable ->
                            val isCurrentActive = courseTable.id == appSettings?.currentCourseTableId
                            val isSelectedForDialog = courseTable.id == selectedTable?.id

                            CourseTablePickerCard(
                                courseTable = courseTable,
                                isSelected = isSelectedForDialog,
                                isCurrentActive = isCurrentActive,
                                onCardClick = {
                                    selectedTable = it
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 快速新建课表按钮（上游同步）
                FilledIconButton(
                    onClick = { showAddTableDialog = true },
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(id = R.string.a11y_add_new_table),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = onDismissRequest,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        selectedTable?.let { onTableSelected(it) }
                        onDismissRequest()
                    },
                    enabled = selectedTable != null
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            }
        },
        dismissButton = null
    )

    // 快速新建课表输入弹窗（上游同步）
    if (showAddTableDialog) {
        val toastAddSuccess = stringResource(R.string.toast_add_table_success, newTableName)
        val toastNameEmpty = stringResource(R.string.toast_name_empty)

        AlertDialog(
            onDismissRequest = {
                showAddTableDialog = false
                newTableName = ""
            },
            title = { Text(stringResource(R.string.dialog_title_add_table)) },
            text = {
                OutlinedTextField(
                    value = newTableName,
                    onValueChange = { newTableName = it },
                    label = { Text(stringResource(R.string.label_table_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // 先捕获局部变量再清空状态：launch 协程异步执行，直接引用状态会被清空后的值覆盖
                    val nameToCreate = newTableName
                    if (nameToCreate.isNotBlank()) {
                        coroutineScope.launch {
                            courseTableRepository.createNewCourseTable(nameToCreate)
                        }
                        Toast.makeText(context, toastAddSuccess, Toast.LENGTH_SHORT).show()

                        showAddTableDialog = false
                        newTableName = ""
                    } else {
                        Toast.makeText(context, toastNameEmpty, Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(stringResource(R.string.action_add))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddTableDialog = false
                    newTableName = ""
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun CourseTablePickerCard(
    courseTable: CourseTable,
    isSelected: Boolean,
    isCurrentActive: Boolean,
    onCardClick: (CourseTable) -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick(courseTable) },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                isCurrentActive -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = courseTable.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(
                        R.string.course_table_id_prefix,
                        courseTable.id.take(8) + "..."
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = stringResource(
                        R.string.course_table_created_at_prefix,
                        dateFormatter.format(Date(courseTable.createdAt))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            if (isCurrentActive) {
                Text(
                    stringResource(R.string.label_current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
