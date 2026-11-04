package com.shiro.classflow.ui.campus.bill

import com.shiro.classflow.data.model.wbu.LegacyBillCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 环形图与年度趋势的纯计算。 */
class LegacyBillChartTest {

    private fun category(name: String, amount: Double) =
        LegacyBillCategory(code = name, name = name, amount = amount)

    @Test
    fun `百分比按总额归一`() {
        val total = 124.72
        assertEquals(1f, legacyBillPercent(124.72, total), 0.0001f)
        assertEquals(0.5f, legacyBillPercent(62.36, total), 0.0001f)
        assertEquals(0f, legacyBillPercent(0.0, total), 0.0001f)
    }

    @Test
    fun `总额为零不除零`() {
        // 原站 ECharts 的 {d}% 在这种情况下会给 NaN%，这里必须给 0
        assertEquals(0f, legacyBillPercent(0.0, 0.0), 0.0001f)
        assertEquals(0f, legacyBillPercent(5.0, 0.0), 0.0001f)
    }

    @Test
    fun `百分比文案一位小数`() {
        assertEquals("100.0%", formatLegacyPercent(1f))
        assertEquals("12.3%", formatLegacyPercent(0.123f))
        assertEquals("0.0%", formatLegacyPercent(0f))
        // 99.99% 不能因为浮点误差显示成 100.0%
        assertEquals("99.9%", formatLegacyPercent(0.9999f))
    }

    @Test
    fun `扇区角度总和是 360`() {
        val slices = legacyBillSlices(
            listOf(category("电子账户消费", 106.72), category("扫码支付", 18.00))
        )
        assertEquals(2, slices.size)
        assertEquals(360f, slices.sumOf { it.sweepAngle.toDouble() }.toFloat(), 0.01f)
        // 从 12 点方向开始
        assertEquals(-90f, slices.first().startAngle, 0.01f)
        // 第二个扇区紧接第一个
        assertEquals(
            slices[0].startAngle + slices[0].sweepAngle,
            slices[1].startAngle,
            0.01f
        )
    }

    @Test
    fun `没有消费时不产生假扇区`() {
        assertTrue(legacyBillSlices(emptyList()).isEmpty())
        assertTrue(legacyBillSlices(listOf(category("电子账户消费", 0.0))).isEmpty())
    }

    @Test
    fun `配色按原站四色循环`() {
        assertEquals(legacyBillColor(0), legacyBillColor(4))
        assertEquals(legacyBillColor(1), legacyBillColor(5))
        // 负数也不越界
        assertEquals(legacyBillColor(3), legacyBillColor(-1))
        assertNotEquals(legacyBillColor(0), legacyBillColor(1))
    }

    @Test
    fun `年份区间上限是当前年`() {
        val range = legacyYearRange(span = 10, currentYear = 2026)
        assertEquals(2026, range.last)
        assertEquals(2016, range.first)
    }

    @Test
    fun `年份区间不会退到 2000 年之前`() {
        val range = legacyYearRange(span = 10, currentYear = 2005)
        assertEquals(2000, range.first)
    }
}
