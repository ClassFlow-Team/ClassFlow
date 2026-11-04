package com.shiro.classflow.ui.campus.bill

import java.util.Locale
import kotlin.math.abs

/** 金额格式化：两位小数（与旧平台 `toFixed(2)` 一致）。 */
internal fun formatLegacyAmount(value: Double): String =
    String.format(Locale.US, "%.2f", value)

/** 带符号金额：消费 `-¥x.xx`，收入 `+¥x.xx`。 */
internal fun formatLegacySignedAmount(amount: Double): String {
    val prefix = if (amount >= 0) "+" else "-"
    return "$prefix¥${formatLegacyAmount(abs(amount))}"
}
