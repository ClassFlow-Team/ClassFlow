package com.shiro.classflow.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 饮水欠费单的判定：`payFlag` 映射、「待支付」口径、能不能自动一卡通扣款、挑哪一单。
 *
 * 口径与 U净 `water-h5` 的订单列表逐字对齐（`payFlag ∈ {0,3,4} && payPrice != 0`），
 * 这几个断言就是防回归用的 —— 改判定先改这里。
 */
class UjingWaterPayStateTest {

    // ── payFlag 映射 ──────────────────────────────────────────────

    @Test
    fun `payFlag 按服务端编码映射`() {
        assertEquals(UjingPayFlag.UNPAID, UjingPayFlag.from(0))
        assertEquals(UjingPayFlag.PAID, UjingPayFlag.from(1))
        assertEquals(UjingPayFlag.REFUNDED, UjingPayFlag.from(2))
        assertEquals(UjingPayFlag.PAYING, UjingPayFlag.from(3))
        assertEquals(UjingPayFlag.PAY_FAILED, UjingPayFlag.from(4))
        assertEquals(UjingPayFlag.REFUNDING, UjingPayFlag.from(5))
        assertEquals(UjingPayFlag.REFUND_FAILED, UjingPayFlag.from(6))
    }

    @Test
    fun `缺失与未知的 payFlag 一律当不知道`() {
        // 关键：不能默认成「未支付」—— 否则每个字段缺失的响应都会被当成欠费去扣钱
        assertEquals(UjingPayFlag.UNKNOWN, UjingPayFlag.from(null))
        assertEquals(UjingPayFlag.UNKNOWN, UjingPayFlag.from(99))
        assertEquals(UjingPayFlag.UNKNOWN, UjingPayFlag.from(-1))
    }

    @Test
    fun `未知状态不算欠费`() {
        assertFalse(UjingPayFlag.UNKNOWN.isOutstanding(9.9))
    }

    // ── 「待支付」口径 ────────────────────────────────────────────

    @Test
    fun `未支付 支付中 支付失败 且金额非零才算欠费`() {
        assertTrue(UjingPayFlag.UNPAID.isOutstanding(1.5))
        assertTrue(UjingPayFlag.PAYING.isOutstanding(1.5))
        assertTrue(UjingPayFlag.PAY_FAILED.isOutstanding(1.5))
    }

    @Test
    fun `已支付 已退款 退款中 不算欠费`() {
        assertFalse(UjingPayFlag.PAID.isOutstanding(1.5))
        assertFalse(UjingPayFlag.REFUNDED.isOutstanding(1.5))
        assertFalse(UjingPayFlag.REFUNDING.isOutstanding(1.5))
        assertFalse(UjingPayFlag.REFUND_FAILED.isOutstanding(1.5))
    }

    @Test
    fun `0 元单不算欠费`() {
        // 0 元单永远结不掉（计量口径 / 免单 / 全额优惠）：算成欠费就会每次进页面都去「补缴」
        assertFalse(UjingPayFlag.UNPAID.isOutstanding(0.0))
        assertFalse(UjingPayFlag.PAY_FAILED.isOutstanding(0.0))
        assertFalse(UjingPayFlag.PAYING.isOutstanding(0.0))
    }

    // ── 能不能自动扣 ──────────────────────────────────────────────

    @Test
    fun `一卡通直扣的欠费单才允许自动结算`() {
        assertTrue(
            canAutoSettleOrder(
                payFlag = 0,
                payPrice = 2.0,
                waterForceSmartCard = 1,
            )
        )
    }

    @Test
    fun `非一卡通直扣一律不自动结算`() {
        // 云闪付 / 微信 / 支付宝 / 电子账户：我们起不来对应钱包，也不能替用户动钱包余额
        assertFalse(
            canAutoSettleOrder(
                payFlag = 0,
                payPrice = 2.0,
                waterForceSmartCard = 0,
            )
        )
        assertFalse(
            canAutoSettleOrder(
                payFlag = 0,
                payPrice = 2.0,
                waterForceSmartCard = null,
            )
        )
    }

    @Test
    fun `已结清的单即使是一卡通也不结算`() {
        assertFalse(
            canAutoSettleOrder(
                payFlag = 1,
                payPrice = 2.0,
                waterForceSmartCard = 1,
            )
        )
    }

    @Test
    fun `金额为 0 的一卡通单不结算`() {
        assertFalse(
            canAutoSettleOrder(
                payFlag = 0,
                payPrice = 0.0,
                waterForceSmartCard = 1,
            )
        )
    }

    // ── 挑哪一单 ──────────────────────────────────────────────────

    private data class Row(val no: String, val flag: Int?, val price: Double)

    @Test
    fun `挑最近一笔欠费 不看设备`() {
        // 欠费是账号级的：昨天在南 B 欠的那单，站在 3 号楼也得先结掉
        val rows = listOf(
            Row("A", 1, 3.0),          // 已支付
            Row("B", 0, 1.5),          // ← 最近一笔欠费
            Row("C", 0, 2.0),
        )
        val picked = pickOutstandingOrder(rows, { it.flag }, { it.price })
        assertEquals("B", picked?.no)
    }

    @Test
    fun `全是已支付时返回 null`() {
        val rows = listOf(Row("A", 1, 3.0), Row("B", 2, 1.0))
        assertNull(pickOutstandingOrder(rows, { it.flag }, { it.price }))
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(pickOutstandingOrder(emptyList<Row>(), { it.flag }, { it.price }))
    }

    @Test
    fun `跳过 0 元欠费去挑真正要付的那笔`() {
        val rows = listOf(
            Row("zero", 0, 0.0),
            Row("real", 4, 0.8),
        )
        val picked = pickOutstandingOrder(rows, { it.flag }, { it.price })
        assertEquals("real", picked?.no)
    }
}
