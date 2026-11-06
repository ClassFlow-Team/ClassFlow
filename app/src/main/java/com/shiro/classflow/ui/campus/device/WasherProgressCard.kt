package com.shiro.classflow.ui.campus.device

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiro.classflow.data.model.wbu.WasherOrderPhase
import com.shiro.classflow.data.model.wbu.WasherOrderSnapshot
import com.shiro.classflow.data.model.wbu.WasherOrderSummary
import com.xingheyuzhuan.shiguangschedule.R
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource

/**
 * 洗衣机页顶部的「进行中订单」卡片。
 *
 * 三种形态：有进行中的单（倒计时 + 进度）、刚结束的单（结果）、没有单（引导去扫码）。
 * 倒计时用 `LaunchedEffect` 每秒刷新一次本机时间，只重算「现在 - 拉取时刻」，
 * 不依赖系统时钟的绝对值（见 `WasherOrderSummary.remainingSeconds`）。
 */
@Composable
fun WasherProgressCard(
    snapshot: WasherOrderSnapshot?,
    loading: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onScan: () -> Unit,
) {
    val order = snapshot?.order
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (order?.phase?.isActive == true) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.LocalLaundryService,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.washer_progress_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.washer_progress_refresh),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            when {
                order == null && loading -> {
                    Text(
                        text = stringResource(R.string.washer_progress_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                order == null -> {
                    Text(
                        text = stringResource(R.string.washer_progress_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.device_action_scan_washer))
                    }
                }

                else -> {
                    WasherOrderBody(order = order, fetchedAt = snapshot.fetchedAt)
                }
            }
        }
    }
}

@Composable
private fun WasherOrderBody(order: WasherOrderSummary, fetchedAt: Long) {
    // 每秒推进一次，倒计时才会走；只在运行中才需要跳
    var now by remember(order.orderId, fetchedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(order.orderId, fetchedAt, order.phase) {
        if (order.phase != WasherOrderPhase.RUNNING) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = order.machineTitle ?: stringResource(R.string.washer_notification_unknown_machine),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            order.cycle?.takeIf { it.isNotBlank() }?.let { cycle ->
                Text(
                    text = cycle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        PhaseBadge(order)
    }

    when (order.phase) {
        WasherOrderPhase.RUNNING -> {
            val remain = order.remainingSeconds(now, fetchedAt)
            val progress by animateFloatAsState(
                targetValue = order.progress(now, fetchedAt),
                label = "washerProgress"
            )
            if (order.workTimeMinutes > 0) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
            }
            Text(
                text = if (remain > 0) {
                    stringResource(R.string.washer_progress_remaining, formatDuration(remain))
                } else {
                    stringResource(R.string.washer_progress_finishing)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.washer_progress_hint_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        WasherOrderPhase.DONE -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.washer_progress_done),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        WasherOrderPhase.ERROR -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = order.statusRemark?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.washer_status_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        WasherOrderPhase.RESERVED, WasherOrderPhase.PAID -> {
            Text(
                text = stringResource(R.string.washer_progress_hint_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        WasherOrderPhase.UNKNOWN -> {
            Text(
                text = stringResource(R.string.washer_status_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (order.payPrice > 0.0) {
        Text(
            text = stringResource(R.string.washer_progress_paid, formatMoney(order.payPrice)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PhaseBadge(order: WasherOrderSummary) {
    val (label, container, content) = when (order.phase) {
        WasherOrderPhase.RUNNING -> Triple(
            order.statusRemark?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.washer_status_running),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary
        )

        WasherOrderPhase.DONE -> Triple(
            order.statusRemark?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.washer_status_done),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )

        WasherOrderPhase.ERROR -> Triple(
            stringResource(R.string.washer_status_error),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )

        WasherOrderPhase.RESERVED -> Triple(
            stringResource(R.string.washer_status_reserved),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )

        WasherOrderPhase.PAID -> Triple(
            stringResource(R.string.washer_status_paid),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )

        WasherOrderPhase.UNKNOWN -> Triple(
            stringResource(R.string.washer_status_unknown),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Surface(shape = MaterialTheme.shapes.small, color = container) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * 提醒设置卡片（默认折叠，点齿轮展开）。
 *
 * 提醒默认关闭：第一版不让用户在不知情的情况下被要求给通知权限，
 * 页面用一条引导条告诉用户「这里可以开」。
 */
@Composable
fun WasherReminderSettingsCard(
    reminderEnabled: Boolean,
    backgroundPolling: Boolean,
    onReminderChange: (Boolean) -> Unit,
    onPollingChange: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.washer_settings_reminder_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.washer_settings_reminder_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = reminderEnabled, onCheckedChange = onReminderChange)
            }

            if (reminderEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.washer_settings_polling_title),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.washer_settings_polling_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = backgroundPolling, onCheckedChange = onPollingChange)
                }
                TextButton(onClick = onCheckNow, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.washer_settings_check_now))
                }
            }
        }
    }
}

/** 提醒没开时页面顶部的一行引导（点了直接展开设置）。 */
@Composable
fun WasherReminderNudge(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.washer_settings_nudge),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClick) {
                Text(stringResource(R.string.washer_settings_nudge_action))
            }
        }
    }
}

/** 秒数 → `12:34` / `1:02:03`。 */
internal fun formatDuration(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

/** 金额统一两位小数（接口给的是元，不是分）。 */
internal fun formatMoney(value: Double): String = "%.2f".format(value)
