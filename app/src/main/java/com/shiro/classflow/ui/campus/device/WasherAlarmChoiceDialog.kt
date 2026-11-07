package com.shiro.classflow.ui.campus.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R

/**
 * 让用户选「用闹钟还是倒计时」的弹窗。
 *
 * 为什么需要选：两者都走系统时钟，但代价不同 ——
 * - 闹钟会留在闹钟列表里，用多了会越积越多（「响后自动删除」是厂商时钟 App 的私有
 *   开关，App 无法替用户勾选）；
 * - 倒计时器天生一次性，响完自动消失，但必须由用户点「开始」。
 *
 * 两个按钮做成整块的大点击区（而不是小小的文字按钮），并配图标：
 * 用户常常是站在洗衣机旁单手操作，盲点也能点中。
 * 倒计时放在右侧 —— 它是更推荐的那个（不留垃圾），符合「右手拇指更容易够到」的习惯。
 */
@Composable
fun WasherAlarmChoiceDialog(
    onDismiss: () -> Unit,
    onPickAlarm: () -> Unit,
    onPickTimer: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.washer_alarm_dialog_title),
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.washer_alarm_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 左：闹钟
                    WasherAlarmChoiceButton(
                        icon = Icons.Filled.Alarm,
                        title = androidx.compose.ui.res.stringResource(R.string.washer_alarm_dialog_alarm),
                        desc = androidx.compose.ui.res.stringResource(R.string.washer_alarm_dialog_alarm_desc),
                        onClick = onPickAlarm,
                        modifier = Modifier.weight(1f),
                    )
                    // 右：倒计时（更推荐：响完自动消失，不占闹钟列表）
                    WasherAlarmChoiceButton(
                        icon = Icons.Filled.HourglassBottom,
                        title = androidx.compose.ui.res.stringResource(R.string.washer_alarm_dialog_timer),
                        desc = androidx.compose.ui.res.stringResource(R.string.washer_alarm_dialog_timer_desc),
                        onClick = onPickTimer,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.washer_alarm_dialog_cancel))
            }
        }
    )
}

/**
 * 弹窗里的一个大号选项按钮：图标 + 标题 + 两行说明，整块可点。
 *
 * 用 Surface + clickable 而不是 Button：Button 的内边距会把内容压得很窄，
 * 图标和两行说明挤不下；这里需要的是「一眼看懂 + 整块好点」。
 */
@Composable
private fun WasherAlarmChoiceButton(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(132.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
