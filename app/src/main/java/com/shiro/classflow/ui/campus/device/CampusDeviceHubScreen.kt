package com.shiro.classflow.ui.campus.device

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.shiro.classflow.data.model.wbu.CampusDeviceEntry
import com.shiro.classflow.data.model.wbu.CampusDeviceKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 校园设备管理页（设置 → 校园服务 → 饮水机 / 洗衣机 / 洗浴）。
 *
 * 与吹风机页同一套骨架：**我用过哪几台**（记录），点开一台就是它的全部动作 ——
 * 直接使用 / 重命名 / 添加到桌面 / 删除。吹风机独有的「判型设置」这里没有：
 * 这三类设备的直达目标由设备身份唯一决定，没有可选的分叉。
 *
 * [kindName] 由导航参数给定（`CampusDeviceKind` 的名字），决定标题、空状态文案
 * 与「扫码添加」按钮的文案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusDeviceHubScreen(
    navBridge: NavBridge,
    kindName: String,
    viewModel: CampusDeviceHubViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var kind by rememberSaveable { mutableStateOf(CampusDeviceKind.WATER) }

    LaunchedEffect(kindName) {
        kind = CampusDeviceKind.fromNameOrNull(kindName) ?: CampusDeviceKind.WATER
        viewModel.start(kindName)
    }

    val state by viewModel.uiState.collectAsState()
    var expandedKey by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<CampusDeviceEntry?>(null) }
    var pinTarget by remember { mutableStateOf<CampusDeviceEntry?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { resId ->
            Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(titleRes(kind)),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navBridge.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.device_section_history),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (state.entries.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Text(
                            text = stringResource(emptyRes(kind)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(state.entries, key = { it.key }) { entry ->
                    DeviceHistoryCard(
                        entry = entry,
                        expanded = expandedKey == entry.key,
                        onToggle = {
                            expandedKey = if (expandedKey == entry.key) null else entry.key
                        },
                        onUse = { navBridge.navigate(entry.launchDestination()) },
                        onRename = { renameTarget = entry },
                        onPin = { pinTarget = entry },
                        onCopyId = {
                            copyToClipboard(context, entry.key)
                            viewModel.notify(R.string.device_message_copied)
                        },
                        onDelete = {
                            expandedKey = null
                            viewModel.delete(entry.key)
                        }
                    )
                }
            }

            item {
                OutlinedButton(
                    onClick = { navBridge.navigate(Destination.QrScan) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(scanActionRes(kind)))
                }
            }
        }
    }

    renameTarget?.let { target ->
        DeviceRenameDialog(
            initial = target.alias.orEmpty(),
            onDismiss = { renameTarget = null },
            onConfirm = { alias ->
                viewModel.rename(target.key, alias)
                renameTarget = null
            }
        )
    }

    pinTarget?.let { target ->
        DeviceIconPickerDialog(
            kind = target.kind,
            selectedIconId = target.iconId,
            onDismiss = { pinTarget = null },
            onConfirm = { iconId ->
                viewModel.setIcon(target.key, iconId)
                val label = target.displayTitle.take(12)
                val result = CampusDeviceShortcuts.addToHome(
                    context = context,
                    entry = target.copy(iconId = iconId),
                    label = label
                )
                viewModel.notify(
                    when (result) {
                        CampusDeviceShortcuts.PinResult.REQUESTED -> R.string.device_message_shortcut_requested
                        CampusDeviceShortcuts.PinResult.UPDATED -> R.string.device_message_shortcut_updated
                        CampusDeviceShortcuts.PinResult.ALREADY_PINNED -> R.string.device_message_shortcut_already
                        CampusDeviceShortcuts.PinResult.UNSUPPORTED -> R.string.device_message_shortcut_unsupported
                        CampusDeviceShortcuts.PinResult.FAILED -> R.string.device_message_shortcut_failed
                    }
                )
                pinTarget = null
            }
        )
    }
}

/** 记录里的「直接使用」：按类型落回各自的直达目的地。 */
private fun CampusDeviceEntry.launchDestination(): Destination = when (kind) {
    CampusDeviceKind.WATER -> Destination.UjingWater(cd = key)

    CampusDeviceKind.WASHER -> Destination.WebApp(
        appId = com.shiro.classflow.data.model.wbu.WebAppId.CAMPUS_CARD.name,
        // raw 为空时按 uuid 重建小天鹅原文（`/wm/` 深链同款写法）
        pendingAutoScan = raw.ifBlank {
            "http://app.littleswan.com/u_download.html?type=Ujing&uuid=$key"
        }
    )

    CampusDeviceKind.SHOWER -> {
        val (system, code, port) =
            com.xingheyuzhuan.shiguangschedule.data.repository.CampusDeviceHubRepository.showerParts(key)
        Destination.ShowerDirect(system = system, code = code, port = port)
    }
}

/** 记录卡片：大标题 / 小标题 / 次数与时间；展开是五个动作。 */
@Composable
private fun DeviceHistoryCard(
    entry: CampusDeviceEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onUse: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onCopyId: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    entry.displaySubtitle?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                KindChip(entry)
            }

            Text(
                text = stringResource(R.string.device_history_use_count, entry.useCount) +
                    lastUsedSuffix(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    // 5 个动作在 360dp 窄屏上一行放不下（「复制机号」会被挤成竖排并裁切），
                    // 用 FlowRow 让它们自然换行。
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        TextButton(onClick = onUse) { Text(stringResource(R.string.device_action_use)) }
                        TextButton(onClick = onRename) { Text(stringResource(R.string.device_action_rename)) }
                        TextButton(onClick = onPin) { Text(stringResource(R.string.device_action_pin_shortcut)) }
                        TextButton(onClick = onCopyId) { Text(stringResource(R.string.device_action_copy_id)) }
                        TextButton(onClick = onDelete) {
                            Text(
                                text = stringResource(R.string.device_action_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 洗浴记录的端口标签（其余类型不显示）。
 *
 * 楼栋（1栋 / 2-3栋）已经写进标题了，这里不再重复；只有多路控水器需要标出端口。
 */
@Composable
private fun KindChip(entry: CampusDeviceEntry) {
    val label = entry.port?.takeIf { it.isNotBlank() }?.let { "#$it" } ?: return
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** 「N 次 · 上次 M」的时间后缀（没有时间记录的旧数据只显示次数）。 */
@Composable
private fun lastUsedSuffix(entry: CampusDeviceEntry): String {
    if (entry.lastUsedAt <= 0L) return ""
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    return " · " + stringResource(
        R.string.device_history_last_used,
        formatter.format(Date(entry.lastUsedAt))
    )
}

/**
 * 复制设备的完整「机号」（即扫码用 id：洗衣机 uuid 整串 / 饮水机 cd / 洗浴 `system:code[:port]`）。
 * 复制的是能拿去扫码 / 深链的原始标识，不是「000002」这种短机号。
 */
private fun copyToClipboard(context: android.content.Context, id: String) {
    val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager ?: return
    manager.setPrimaryClip(
        android.content.ClipData.newPlainText(
            context.getString(R.string.device_action_copy_id), id
        )
    )
}

@Composable
private fun titleRes(kind: CampusDeviceKind): Int = when (kind) {
    CampusDeviceKind.WATER -> R.string.title_device_hub_water
    CampusDeviceKind.WASHER -> R.string.title_device_hub_washer
    CampusDeviceKind.SHOWER -> R.string.title_device_hub_shower
}

@Composable
private fun emptyRes(kind: CampusDeviceKind): Int = when (kind) {
    CampusDeviceKind.WATER -> R.string.device_history_empty_water
    CampusDeviceKind.WASHER -> R.string.device_history_empty_washer
    CampusDeviceKind.SHOWER -> R.string.device_history_empty_shower
}

@Composable
private fun scanActionRes(kind: CampusDeviceKind): Int = when (kind) {
    CampusDeviceKind.WATER -> R.string.device_action_scan_water
    CampusDeviceKind.WASHER -> R.string.device_action_scan_washer
    CampusDeviceKind.SHOWER -> R.string.device_action_scan_shower
}
