package com.shiro.classflow.ui.campus.device

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
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiro.classflow.data.model.wbu.WasherOrderPhase
import com.shiro.classflow.data.model.wbu.WasherOrderSummary
import com.xingheyuzhuan.shiguangschedule.R

/**
 * 单条洗衣订单详情。
 *
 * 为什么要单独开弹层：`orders/history` 的列表项**不带金额**（实测只给 `payFlag`），
 * 金额只存在于 `orders/{id}/detail`。列表上与其显示一排「0.00」或「—」，
 * 不如让用户点开看真正的数字 —— 这与官方 H5 的「我的订单 → 详情」是同一个动线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WasherBillDetailSheet(
    target: WasherHistoryDetailTarget,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val order = target.shown

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.LocalLaundryService,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = order.machineTitle
                            ?: stringResource(R.string.washer_notification_unknown_machine),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = order.statusRemark?.takeIf { it.isNotBlank() }
                            ?: stringResource(
                                if (order.phase.isTerminal) R.string.washer_status_done
                                else R.string.washer_status_unknown
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 金额是这个弹层的主要目的，单独放大显示
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = stringResource(R.string.washer_bill_detail_paid),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Spacer(Modifier.weight(1f))
                if (target.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else if (order.hasKnownPrice) {
                    Text(
                        text = "¥${formatMoney(order.payPrice)}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = stringResource(R.string.washer_bill_detail_price_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            order.cycle?.takeIf { it.isNotBlank() }?.let {
                BillDetailRow(stringResource(R.string.washer_bill_detail_program), it)
            }
            formatDate(order.createAt)?.let {
                BillDetailRow(stringResource(R.string.washer_bill_detail_time), it)
            }
            order.deviceNo?.takeIf { it.isNotBlank() }?.let {
                BillDetailRow(stringResource(R.string.washer_bill_detail_device_no), it)
            }
            if (order.orderId > 0L) {
                BillDetailRow(stringResource(R.string.washer_bill_detail_order_id), order.orderId.toString())
            }

            target.errorRes?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.washer_bill_detail_close))
            }
        }
    }
}

@Composable
private fun BillDetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}
