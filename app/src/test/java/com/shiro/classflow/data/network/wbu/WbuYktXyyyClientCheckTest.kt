package com.shiro.classflow.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1 栋 `CheckKsPos` 响应归类。
 *
 * 这里钉住的核心区别是「**知道结论**」与「**不知道结论**」：
 * - 机号无效（`-4` / `无效`）→ [WbuYktXyyyClient.CheckResult.Invalid]；
 * - 机号有效但设备加锁/故障 → [WbuYktXyyyClient.CheckResult.Blocked]：机号是真的，
 *   必须放行进页面（由 H5 自己提示设备状态），不能报成网络故障；
 * - 其余才是不确定（[WbuYktXyyyClient.CheckResult.Unavailable]）。
 *
 * 真机原文：`RetDsp = 设备加锁或故障报警，暂不能交易`
 * （过去它落进 `Unavailable`，用户看到的是「连接校园服务失败」）。
 */
class WbuYktXyyyClientCheckTest {

    private fun classify(retNo: Int, retDsp: String, costDsp: String = "") =
        WbuYktXyyyClient.classifyCheckKsPos(retNo = retNo, retDsp = retDsp, costDsp = costDsp)

    // ───────────────── 正常命中 ─────────────────

    @Test
    fun validCarriesAreaName() {
        val result = classify(retNo = 0, retDsp = "", costDsp = "马影河宿舍1栋")
        assertEquals(WbuYktXyyyClient.CheckResult.Valid("马影河宿舍1栋"), result)
    }

    @Test
    fun validDropsPlaceholderAreaName() {
        // 未命中具体区域时服务端回默认值「控水定额消费」，它不是位置名
        val result = classify(retNo = 0, retDsp = "", costDsp = "控水定额消费")
        assertEquals(WbuYktXyyyClient.CheckResult.Valid(null), result)
    }

    @Test
    fun validWithBlankAreaName() {
        assertEquals(WbuYktXyyyClient.CheckResult.Valid(null), classify(retNo = 0, retDsp = ""))
    }

    // ───────────────── 机号无效 ─────────────────

    @Test
    fun retNoMinusFourIsInvalid() {
        assertEquals(WbuYktXyyyClient.CheckResult.Invalid, classify(retNo = -4, retDsp = ""))
    }

    @Test
    fun invalidTextIsInvalidRegardlessOfRetNo() {
        assertEquals(WbuYktXyyyClient.CheckResult.Invalid, classify(retNo = -1, retDsp = "机号无效"))
    }

    // ───────────────── 设备状态：加锁 / 故障（必须放行） ─────────────────

    @Test
    fun lockedDeviceIsBlockedNotUnavailable() {
        val result = classify(
            retNo = -1,
            retDsp = "设备加锁或故障报警，暂不能交易",
            costDsp = "马影河宿舍1栋"
        )
        assertTrue("expected Blocked, got $result", result is WbuYktXyyyClient.CheckResult.Blocked)
        result as WbuYktXyyyClient.CheckResult.Blocked
        assertEquals("设备加锁或故障报警，暂不能交易", result.reason)
        // 机号是有效的，位置名照样要带出来写使用记录
        assertEquals("马影河宿舍1栋", result.classLabel)
    }

    @Test
    fun eachStatusKeywordIsBlocked() {
        listOf("加锁", "故障", "暂停", "停用", "维修").forEach { keyword ->
            val result = classify(retNo = -2, retDsp = "设备${keyword}中")
            assertTrue("「$keyword」应归 Blocked，实际 $result", result is WbuYktXyyyClient.CheckResult.Blocked)
        }
    }

    @Test
    fun blockedWithoutAreaNameStillBlocked() {
        val result = classify(retNo = -1, retDsp = "设备故障", costDsp = "控水定额消费")
        assertEquals(
            WbuYktXyyyClient.CheckResult.Blocked(null, "设备故障"),
            result
        )
    }

    // ───────────────── 其它：无法判定 ─────────────────

    @Test
    fun unknownMessageIsUnavailable() {
        val result = classify(retNo = -9, retDsp = "服务器忙")
        assertEquals(WbuYktXyyyClient.CheckResult.Unavailable("服务器忙"), result)
    }

    @Test
    fun blankUnknownMessageKeepsNullReason() {
        val result = classify(retNo = Int.MIN_VALUE, retDsp = "   ")
        assertTrue(result is WbuYktXyyyClient.CheckResult.Unavailable)
        assertNull((result as WbuYktXyyyClient.CheckResult.Unavailable).message)
    }

    @Test
    fun trimsReason() {
        val result = classify(retNo = -1, retDsp = "  设备加锁  ")
        assertEquals("设备加锁", (result as WbuYktXyyyClient.CheckResult.Blocked).reason)
    }
}
