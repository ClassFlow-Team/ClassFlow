package com.shiro.classflow.ui.campus.device

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R
import com.shiro.classflow.data.model.wbu.CampusDeviceKind
import com.shiro.classflow.data.network.wbu.CampusShowerLink
import com.shiro.classflow.data.network.wbu.ShowerManualCode

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
 * 手动输入洗浴设备码（机号 / 设备号 / 整条链接）。
 *
 * 系统由**用户勾选**，不做自动判型：2-3 栋的 `getDevicesType` 实测不校验设备是否存在（拿
 * `10101`、`99999999` 去问都回 `devicesType=101`），任何「猜系统」的方案都会替用户编造一个
 * 成功答案。输入里已经写明系统的（`/s/` 深链、`机号$#$端口`、洗浴码载体链接、1 栋扫码裸码）
 * 则锁定到那个系统，勾选框只作展示。
 *
 * 只有数字时**必须由用户勾选**，不预选也不猜：2-3 栋的接口不会说不，猜错就直接进到别人的
 * 淋浴页（真机上就这样把 1 栋机号 `10101` 记成了「2-3栋 10101」）。
 */
@Composable
fun DeviceManualShowerDialog(
    onDismiss: () -> Unit,
    /**
     * 正在解析（在弹窗里原地转圈）。
     *
     * 手动输入是**在 App 内的操作**，不需要品牌全屏过场：过去这里跳 `ShowerDirect` 落地页，
     * 用户会先看到一大片蓝底再切到目标页，两次画面切换都很突兀。现在解析在弹窗里完成，
     * 成功才一次性导航到目标页。
     */
    resolving: Boolean = false,
    /** 解析失败的一句话（显示在弹窗里，用户可以直接改输入重试）。 */
    errorText: String? = null,
    onConfirm: (system: String, code: String, port: String?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val plan = remember(text) {
        if (text.isBlank()) null else ShowerManualCode.parse(text)
    }
    val ambiguous = plan as? ShowerManualCode.Result.Ambiguous
    val pinnedSystem = (plan as? ShowerManualCode.Result.Pinned)?.candidate?.system
    // 系统由用户手动勾选；输入里已经写明系统时锁定，不再让选
    var picked by remember(plan) { mutableStateOf<String?>(null) }
    val system = pinnedSystem ?: picked
    // 勾了系统却收不下这串输入（1 栋只有 5 位、不接受端口）时给出原因并禁用确定
    val candidate = when (val p = plan) {
        is ShowerManualCode.Result.Pinned -> p.candidate
        is ShowerManualCode.Result.Ambiguous -> system?.let { ShowerManualCode.resolve(p, it) }
        else -> null
    }
    val rejectReason = when {
        text.isBlank() -> null
        plan is ShowerManualCode.Result.Unparsed ->
            stringResource(R.string.device_manual_shower_detect_invalid)

        // 只有数字时必须先勾楼栋：不猜，猜错就是进到别人的淋浴页
        ambiguous != null && system == null ->
            stringResource(R.string.device_manual_shower_pick_required)

        candidate == null -> stringResource(R.string.device_manual_shower_system_mismatch)
        else -> null
    }

    AlertDialog(
        // 解析中不允许关闭：否则用户以为取消了，结果又跳走
        onDismissRequest = { if (!resolving) onDismiss() },
        title = { Text(stringResource(R.string.device_manual_shower_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    enabled = !resolving,
                    placeholder = { Text(stringResource(R.string.device_manual_shower_placeholder)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.device_manual_shower_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.device_manual_shower_pick_system),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 竖排：两个选项的文案都比较长，横排在窄屏上会被挤成一行几个字
                Column {
                    listOf(
                        CampusShowerLink.SYSTEM_YKT to R.string.device_manual_shower_system_ykt,
                        CampusShowerLink.SYSTEM_LIFE to R.string.device_manual_shower_system_life
                    ).forEach { (value, labelRes) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = pinnedSystem == null) { picked = value },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = system == value,
                                onClick = { if (pinnedSystem == null) picked = value },
                                enabled = pinnedSystem == null
                            )
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (pinnedSystem == null) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }

                // 判型锁定 / 收下 / 收不下 三种状态都写清楚，别让用户猜为什么按钮是灰的
                val hint = when {
                    errorText != null -> errorText
                    rejectReason != null -> rejectReason
                    candidate != null -> stringResource(
                        if (candidate.system == CampusShowerLink.SYSTEM_YKT) {
                            R.string.device_manual_shower_detect_ykt
                        } else {
                            R.string.device_manual_shower_detect_life
                        },
                        candidate.code
                    ) + (candidate.port?.let {
                        " · " + stringResource(R.string.device_manual_shower_detect_port, it)
                    } ?: "") + if (pinnedSystem != null) {
                        // 链接里本来就有系统，说明「设备码是从链接里取的」，免得用户以为要整条当设备号
                        " · " + stringResource(
                            if (text.contains("://")) {
                                R.string.device_manual_shower_detect_link
                            } else {
                                R.string.device_manual_shower_detect_from_input
                            }
                        )
                    } else {
                        ""
                    }

                    else -> null
                }
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (errorText != null || rejectReason != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = candidate ?: return@TextButton
                    onConfirm(target.system, target.code, target.port)
                },
                enabled = candidate != null && !resolving
            ) {
                if (resolving) {
                    // 原地转圈：解析就在这个弹窗里跑，不再跳蓝色全屏过场
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.device_manual_shower_resolving))
                } else {
                    Text(stringResource(R.string.action_ok))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !resolving) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * 改一台洗浴设备的类型（1 栋 ⇄ 2-3 栋）。
 *
 * 类型不是「显示用的标签」而是**设备身份的一部分**：`y` 走 1 栋 yktxyyy（5 位机号、
 * `CheckKsPos` 严格校验），`l` 走 2-3 栋 lifeService（水表设备号，可带端口）。所以改类型
 * 必须同时校验「这串号码在新系统里收得下」，否则会造出一条点了就报错的记录。
 *
 * 这里只改类型，不改设备号本身：号码是贴纸上的事实，改错号码应该重新手输 / 扫码。
 */
@Composable
fun DeviceShowerSystemDialog(
    currentSystem: String?,
    code: String,
    port: String?,
    onDismiss: () -> Unit,
    onConfirm: (system: String, code: String, port: String?) -> Unit,
) {
    // 认不出当前系统（老数据 / 手改过 key）时不预选，逼用户明确选一次
    var picked by remember { mutableStateOf(currentSystem) }
    var portText by remember { mutableStateOf(port.orEmpty()) }
    // 1 栋没有端口概念；切过去时先别把用户填的端口丢掉，切回来还能用
    val effectivePort = if (picked == CampusShowerLink.SYSTEM_YKT) null else {
        portText.trim().takeIf { it.isNotEmpty() }
    }
    val candidate = picked?.let { ShowerManualCode.normalize(it, code, effectivePort) }
    val rejectReason = when {
        picked == null -> stringResource(R.string.device_shower_system_pick_required)
        candidate == null -> stringResource(R.string.device_shower_system_mismatch, code)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_shower_system_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.device_shower_system_device, code),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                // 竖排：两个选项文案都比较长，横排会被挤成一行几个字
                Column {
                    listOf(
                        CampusShowerLink.SYSTEM_YKT to R.string.device_manual_shower_system_ykt,
                        CampusShowerLink.SYSTEM_LIFE to R.string.device_manual_shower_system_life
                    ).forEach { (value, labelRes) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picked = value },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = picked == value,
                                onClick = { picked = value }
                            )
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // 只有 2-3 栋有「多路控水器端口」；切到 1 栋时这行直接不显示
                if (picked == CampusShowerLink.SYSTEM_LIFE) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.device_shower_system_port_label)) },
                        supportingText = {
                            Text(stringResource(R.string.device_shower_system_port_hint))
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (rejectReason != null) {
                    Text(
                        text = rejectReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = candidate ?: return@TextButton
                    onConfirm(target.system, target.code, target.port)
                },
                enabled = candidate != null
            ) { Text(stringResource(R.string.action_save)) }
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
