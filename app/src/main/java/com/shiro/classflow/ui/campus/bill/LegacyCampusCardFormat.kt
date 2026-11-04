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

/**
 * 列表行的日期时间：`2026-11-01 18:36:18` → `11-01 18:36`。
 *
 * 列表外层已有按月的分组表头（「本月」/「2026年10月」），年份不必重复；
 * 但只写 `18:36` 会让同一屏里跨月的数据分不清是哪天，所以月日必须带上。
 *
 * 不做严格校验：取不到 `MM-dd HH:mm` 时退化成开头的 `HH:mm`，连时分都不像就返回原串 ——
 * 解析层真出问题时宁可显示怪字符串，也不要吞掉整条记录的时间。
 */
internal fun formatLegacyEntryDateTime(occurredAt: String): String {
    val text = occurredAt.trim()
    if (text.length >= 16 && text[10] == ' ') {
        val monthDay = text.substring(5, 10)
        val clock = text.substring(11, 16)
        if (looksLikeMonthDay(monthDay) && looksLikeClock(clock)) return "$monthDay $clock"
    }
    val clock = text.take(5)
    return if (looksLikeClock(clock)) clock else text
}

/** `11-01`。 */
private fun looksLikeMonthDay(value: String): Boolean =
    value.length == 5 && value[2] == '-' &&
        value[0].isDigit() && value[1].isDigit() && value[3].isDigit() && value[4].isDigit()

/** `18:36`。 */
private fun looksLikeClock(value: String): Boolean =
    value.length == 5 && value[2] == ':' &&
        value[0].isDigit() && value[1].isDigit() && value[3].isDigit() && value[4].isDigit()
