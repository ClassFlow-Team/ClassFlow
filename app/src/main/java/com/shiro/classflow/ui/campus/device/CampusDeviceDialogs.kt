package com.shiro.classflow.ui.campus.device

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R
import com.shiro.classflow.data.model.wbu.CampusDeviceKind

/**
 * 校园设备管理页的通用对话框：重命名、选图标。
 *
 * 从吹风机页的同款对话框抽出来共用 —— 长得一样、行为一样，只是文案不带「吹风机」。
 * 吹风机页保留自己那份不动（那边文案与图标集是自成体系的）。
 */

/** 重命名对话框：空输入 = 清掉别名（回退到店铺名 / 默认标题）。 */
@Composable
fun DeviceRenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.device_rename_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

/**
 * 选图标 → 添加到桌面（预设图标，每台设备挑一个花色就行）。
 *
 * 默认选中该类型的设备轮廓图标；[selectedIconId] 为空时也回落到它。
 */
@Composable
fun DeviceIconPickerDialog(
    kind: CampusDeviceKind,
    selectedIconId: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selected by remember {
        mutableStateOf(selectedIconId ?: CampusDeviceShortcuts.defaultIconId(kind))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_icon_picker_title)) },
        text = {
            Column {
                // 一行 5 个：14 个预设图标排三行，对话框宽度不够时也不至于被裁掉
                CampusDeviceShortcuts.ICONS.chunked(5).forEach { row ->
                    Row(
                        modifier = Modifier.padding(vertical = 6.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { preset ->
                            val isSelected = preset.id == selected
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .clickable { selected = preset.id },
                                contentAlignment = Alignment.Center
                            ) {
                                // 用 Image 而不是 Icon：Icon 会按内容色给整张图染色，
                                // 这些预设图标是「彩色底 + 白色图形」，染色后只剩一坨纯色
                                Image(
                                    painter = painterResource(preset.resId),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.device_icon_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.device_action_pin_shortcut))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
