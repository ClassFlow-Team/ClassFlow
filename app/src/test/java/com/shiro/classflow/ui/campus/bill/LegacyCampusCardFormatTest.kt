package com.shiro.classflow.ui.campus.bill

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 旧平台列表行的日期时间格式。
 *
 * 这里只钉「`yyyy-MM-dd HH:mm:ss` → `MM-dd HH:mm`」和几条降级路径：
 * 列表外层已有月份分组表头，年份不重复；但月日必须留着，
 * 否则同一屏里跨月的数据只剩 `18:36`，分不清是哪天。
 */
class LegacyCampusCardFormatTest {

    @Test
    fun `完整时间截成月日加时分`() {
        assertEquals("11-01 18:36", formatLegacyEntryDateTime("2026-11-01 18:36:18"))
        assertEquals("10-08 21:17", formatLegacyEntryDateTime("2026-10-08 21:17:58"))
    }

    @Test
    fun `首尾空白不影响解析`() {
        assertEquals("11-01 18:36", formatLegacyEntryDateTime("  2026-11-01 18:36:18  "))
    }

    @Test
    fun `只有时分时原样给出时分`() {
        assertEquals("18:36", formatLegacyEntryDateTime("18:36:18"))
    }

    @Test
    fun `只有日期时原样返回`() {
        assertEquals("2026-11-01", formatLegacyEntryDateTime("2026-11-01"))
    }

    @Test
    fun `空串返回空串`() {
        assertEquals("", formatLegacyEntryDateTime(""))
        assertEquals("", formatLegacyEntryDateTime("   "))
    }

    @Test
    fun `无法识别的格式原样返回`() {
        assertEquals("someday", formatLegacyEntryDateTime("someday"))
    }
}
