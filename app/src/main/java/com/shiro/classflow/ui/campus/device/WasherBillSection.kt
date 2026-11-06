package com.shiro.classflow.ui.campus.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiro.classflow.data.model.wbu.WasherOrderSummary
import com.xingheyuzhuan.shiguangschedule.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 洗衣账单（`orders/history`）。
 *
 * 只做「我洗过几次、花了多少钱」，不做一卡通总流水 —— 那在
 * `Destination.CampusCardBill` 里，两者语义不同：这里是洗衣消费，那边是卡余额变动。
 */
@Composable
fun WasherBillSection(
    orders: List<WasherOrderSummary>,
    loading: Boolean,
    errorRes: Int?,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.washer_bill_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (orders.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.washer_bill_summary,
                        orders.size,
                        formatMoney(orders.sumOf { it.payPrice })
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when {
            orders.isEmpty() && loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.washer_bill_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            orders.isEmpty() -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Text(
                        text = errorRes?.let { stringResource(it) }
                            ?: stringResource(R.string.washer_bill_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            else -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column {
                        orders.forEachIndexed { index, order ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                            WasherBillRow(order)
                        }
                        if (hasMore) {
                            TextButton(
                                onClick = onLoadMore,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !loading
                            ) {
                                Text(
                                    stringResource(
                                        if (loading) R.string.washer_bill_loading_more
                                        else R.string.washer_bill_load_more
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WasherBillRow(order: WasherOrderSummary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.LocalLaundryService,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = order.machineTitle
                    ?: stringResource(R.string.washer_notification_unknown_machine),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    formatDate(order.createAt),
                    order.cycle?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatMoney(order.payPrice),
                style = MaterialTheme.typography.bodyMedium,
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
}

/**
 * `createAt` 是 ISO8601（`2026-10-16T14:27:04Z`），只取到分钟展示。
 *
 * 解析失败就原样返回前 16 个字符 —— 账单里显示一条原始时间也比显示空白强。
 */
private fun formatDate(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val parsed = runCatching {
        // 服务端固定 UTC（末尾 Z）；换成毫秒手动加，避免依赖 API 26 才有的 java.time 解析格式
        val normalized = iso.replace("Z", "+0000")
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(normalized)
    }.getOrNull() ?: return iso.take(16).replace('T', ' ')
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(parsed.time))
}
