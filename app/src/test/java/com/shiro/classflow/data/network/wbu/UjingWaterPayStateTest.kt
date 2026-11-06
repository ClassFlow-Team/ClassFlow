package com.shiro.classflow.data.network.wbu

import com.shiro.classflow.ui.campus.ujing.blocksDispensing
import com.shiro.classflow.ui.campus.ujing.isRetriableSettleFailure
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

    // ── 错误码语义 ────────────────────────────────────────────────

    /**
     * `2040224` 才是本校实测挡住下单的码；`1532` 是官方 H5 里的支付宝分支。
     * 两个都算「有单没结束」，漏掉任何一个都会让用户卡在「取水失败」上。
     */
    @Test
    fun `两个订单未结束的码都算挡住下单`() {
        assertTrue(
            WbuUjingClient.UjingApiException(WbuUjingClient.CODE_ORDER_NOT_FINISHED, "您有订单未结束")
                .blocksDispensing()
        )
        assertTrue(
            WbuUjingClient.UjingApiException(WbuUjingClient.CODE_UNPAID_ORDER, "您有支付宝订单未结账")
                .blocksDispensing()
        )
    }

    @Test
    fun `设备忙与普通失败不算挡住下单`() {
        // 设备忙是「等一会儿再来」，不是「先去结账」——两者给的出路完全不同
        assertFalse(
            WbuUjingClient.UjingApiException(WbuUjingClient.CODE_DEVICE_BUSY, "设备正在使用中")
                .blocksDispensing()
        )
        assertFalse(
            WbuUjingClient.UjingApiException(2040240, "不支持该下单方式")
                .blocksDispensing()
        )
    }

    /**
     * `1021` 是一卡通扣款没成功（实测错误码 80 = 余额不足），充值后能成功，
     * 所以必须当成**可重试**，不能归到「服务端拒绝」里把用户劝退。
     */
    @Test
    fun `一卡通扣款失败是可重试的`() {
        assertTrue(
            isRetriableSettleFailure(
                WbuUjingClient.UjingApiException(
                    WbuUjingClient.CODE_CARD_PAY_FAILED,
                    "一卡通异常，请联系一卡通处理：支付未成功，错误码：80"
                )
            )
        )
    }

    @Test
    fun `其它业务码不是可重试的扣款失败`() {
        assertFalse(isRetriableSettleFailure(WbuUjingClient.UjingApiException(2040240, "不支持该下单方式")))
        assertFalse(isRetriableSettleFailure(WbuUjingClient.UjingApiException(6020208, "请勿重复请求")))
    }

    // ── 卡在「等待结账」 ──────────────────────────────────────────

    /**
     * 真机实测：一卡通扣款失败后订单永久停在
     * `orderStatus = 30「取水结束，等待结账」+ payFlag = 4「支付失败」`。
     * 不认这种情况，取水页就会一直显示「正在取水」，补缴入口永远出不来。
     */
    @Test
    fun `30 加支付失败就是卡住了`() {
        assertTrue(isSettlementStuck(30, 4))
    }

    @Test
    fun `正常结账中的 30 不算卡住`() {
        // 正常的「结账中」payFlag 是未支付 / 支付中，几秒就过去了，不能当成结束
        assertFalse(isSettlementStuck(30, 0))
        assertFalse(isSettlementStuck(30, 3))
        assertFalse(isSettlementStuck(30, null))
    }

    @Test
    fun `别的状态配支付失败不算卡在结账`() {
        // 取水中（10）支付失败 → 水还在出，不该跳完成页
        assertFalse(isSettlementStuck(10, 4))
        assertFalse(isSettlementStuck(0, 4))
    }

    @Test
    fun `终态与卡住的订单都算取水结束`() {
        fun detail(status: Int, payFlag: Int?, paid: Double = 0.04) = WbuUjingClient.WaterOrderDetail(
            orderId = 1, orderNo = "n", orderStatus = status, orderStatusName = "", storeName = null,
            deviceNo = null, orderTypeName = null, payTypeName = null, hotWaterMl = 0, warmWaterMl = 0,
            payPrice = paid, isTerminal = status in WbuUjingClient.TERMINAL_STATUS, payFlag = payFlag
        )
        assertTrue(isDispenseDone(detail(50, 1)))
        assertTrue(isDispenseDone(detail(65, 4)))
        assertTrue(isDispenseDone(detail(62, 4)))
        // 卡在等待结账的那一种
        assertTrue(isDispenseDone(detail(30, 4)))
        // 还在取水 / 正常结账中
        assertFalse(isDispenseDone(detail(10, 0)))
        assertFalse(isDispenseDone(detail(30, 0)))
    }

    // ── 补缴去重 / 退避 ──────────────────────────────────────────

    /**
     * 真机 bug 的防回归：以前一处用 `orderNo`、一处用 `orderId` 做 key，
     * 两个集合永远对不上 → 每次进页面都补缴两次 → 第二次回 `6020208 请勿重复请求`。
     * 现在统一用 orderId。
     */
    @Test
    fun `同一单失败后进入退避窗口不再重复请求`() {
        val id = 1236770620L
        val now = 1_000_000L
        val backoff = mapOf(id to now + 30_000L)

        // 窗口内：不许再打（正是以前重复请求的来源）
        assertFalse(canAttemptSettle(id, settled = emptySet(), backoffUntil = backoff, now = now, force = false))
        // 窗口过后：放行（用户充完钱重进页面要能自动再试）
        assertTrue(
            canAttemptSettle(id, settled = emptySet(), backoffUntil = backoff, now = now + 31_000L, force = false)
        )
    }

    @Test
    fun `已经结清过的单永远不再打`() {
        val id = 42L
        val now = 1_000_000L
        // 即使 force，也不该对已经结清的单再发一次扣款请求
        assertFalse(canAttemptSettle(id, settled = setOf(id), backoffUntil = emptyMap(), now = now, force = false))
        assertFalse(canAttemptSettle(id, settled = setOf(id), backoffUntil = emptyMap(), now = now, force = true))
    }

    @Test
    fun `用户主动点按钮可以无视退避`() {
        val id = 7L
        val now = 1_000_000L
        val backoff = mapOf(id to now + 30_000L)
        assertTrue(canAttemptSettle(id, settled = emptySet(), backoffUntil = backoff, now = now, force = true))
    }

    @Test
    fun `没任何记录时首次一定放行`() {
        assertTrue(
            canAttemptSettle(1L, settled = emptySet(), backoffUntil = emptyMap(), now = 0L, force = false)
        )
    }
}
