package com.shiro.classflow.ui.campus.bill

import androidx.compose.ui.graphics.Color
import com.shiro.classflow.data.model.wbu.LegacyBillCategory
import java.time.Year

/**
 * 月账单环形图的配色 —— **原站 ECharts 配置里的原值**：
 * `color: ['#f97c6f', '#269be5', '#b2ecfb', '#45d3b5']`（`PPage/ComePage` 的 `loadmyChart2`）。
 * 分类多于 4 个时循环取用。
 */
internal val LegacyBillPalette = listOf(
    Color(0xFFF97C6F),
    Color(0xFF269BE5),
    Color(0xFFB2ECFB),
    Color(0xFF45D3B5)
)

/** 取第 [index] 个分类的颜色；负数与越界都安全。 */
internal fun legacyBillColor(index: Int): Color =
    LegacyBillPalette[((index % LegacyBillPalette.size) + LegacyBillPalette.size) % LegacyBillPalette.size]

/**
 * 某个分类占总额的百分比。
 *
 * 总额为 0 时返回 0（原站 `{d}%` 在总额为 0 时会给出 `NaN%`，这里不重复那个毛病）。
 */
internal fun legacyBillPercent(amount: Double, total: Double): Float {
    if (total <= 0.0 || !total.isFinite() || !amount.isFinite()) return 0f
    return (amount / total).toFloat().coerceIn(0f, 1f)
}

/** 百分比展示文案：`12.3%`。 */
internal fun formatLegacyPercent(percent: Float): String {
    val scaled = (percent * 1000f).toInt()
    val whole = scaled / 10
    val decimal = scaled % 10
    return "$whole.$decimal%"
}

/**
 * 环形图扇区：起始角、扫过角度（度）。
 *
 * 起始角从 12 点方向（-90°）顺时针。总额为 0 或没有分类时返回空列表，
 * 让调用方直接跳过绘制，而不是画一个 0 度的假扇区。
 */
internal fun legacyBillSlices(categories: List<LegacyBillCategory>): List<LegacyBillSlice> {
    val total = categories.sumOf { it.amount }
    if (total <= 0.0) return emptyList()
    var start = -90f
    return categories.map { category ->
        val sweep = (category.amount / total).toFloat() * 360f
        val slice = LegacyBillSlice(startAngle = start, sweepAngle = sweep, amount = category.amount)
        start += sweep
        slice
    }
}

/** 一个扇区的几何与数值。 */
internal data class LegacyBillSlice(
    val startAngle: Float,
    val sweepAngle: Float,
    val amount: Double
)

/**
 * 年度趋势能切换的年份区间。
 *
 * 上限是**当前年**（未来不可能有账单），下限是当前年往前 [span] 年。
 * `EveryMonthPay` 实测支持任意年份：2026 `maxamt=124.72`、2025 `155.19`、
 * 2024/2023 全 0（不是报错，是确实没有消费）。
 */
internal fun legacyYearRange(span: Int = 10, currentYear: Int = Year.now().value): IntRange =
    (currentYear - span).coerceAtLeast(2000)..currentYear
