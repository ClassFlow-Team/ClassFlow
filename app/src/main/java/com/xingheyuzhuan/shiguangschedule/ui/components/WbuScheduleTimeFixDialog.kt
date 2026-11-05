package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.shiro.classflow.data.network.wbu.MyhTimeSlotCorrection
import com.xingheyuzhuan.shiguangschedule.R

/**
 * 马影河作息修正询问框（导入后 / 老版本升级首启各一次）。
 *
 * 只问一件事：教务给的第 4 节下课时间是不是要按真实作息改掉。
 * 「不再提示」勾上后，档位跟着这次的选择走（改了 → 以后自动改；没改 → 以后都不处理），
 * 语义定义在 [MyhTimeSlotCorrection.modeAfterPrompt]，这里不重复判断。
 *
 * @param fix 命中的修正目标（由 [MyhTimeSlotCorrection.detect] 给出），文案里的两个时间取自它。
 * @param onDismiss 用户返回 / 点外部关闭：等同「这次不处理」，不写任何偏好。
 * @param onConfirm `apply` 表示是否修正，`noMorePrompts` 表示是否勾了「不再提示」。
 */
@Composable
fun WbuScheduleTimeFixDialog(
    fix: MyhTimeSlotCorrection.Fix,
    onDismiss: () -> Unit,
    onConfirm: (apply: Boolean, noMorePrompts: Boolean) -> Unit
) {
    var noMorePrompts by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_myh_time_fix, fix.section)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.msg_myh_time_fix,
                        fix.section,
                        fix.fromEnd,
                        fix.toEnd
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.desc_myh_time_fix_reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { noMorePrompts = !noMorePrompts }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = noMorePrompts,
                        onCheckedChange = { noMorePrompts = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.label_do_not_ask_again),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(true, noMorePrompts) }) {
                Text(stringResource(R.string.action_fix_to_real_time, fix.toEnd))
            }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(false, noMorePrompts) }) {
                Text(stringResource(R.string.action_keep_school_time))
            }
        }
    )
}
