package com.shiro.classflow.ui.campus.bill

import org.junit.Assert.assertEquals
import org.junit.Test

/** 旧平台金额展示：单位是元，消费带负号、收入带正号。 */
class LegacyCampusCardAmountTest {

    @Test
    fun `金额固定两位小数`() {
        assertEquals("0.38", formatLegacyAmount(0.38))
        assertEquals("100.00", formatLegacyAmount(100.0))
        assertEquals("-0.38", formatLegacyAmount(-0.38))
    }

    @Test
    fun `消费显示负号收入显示正号`() {
        assertEquals("-¥0.38", formatLegacySignedAmount(-0.38))
        assertEquals("+¥100.00", formatLegacySignedAmount(100.0))
        assertEquals("+¥0.00", formatLegacySignedAmount(0.0))
    }
}
