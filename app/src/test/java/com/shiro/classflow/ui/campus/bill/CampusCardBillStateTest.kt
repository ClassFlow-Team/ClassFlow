package com.shiro.classflow.ui.campus.bill

import com.shiro.classflow.data.model.wbu.LegacyCampusCardEntry
import com.shiro.classflow.data.network.wbu.AccessFailure
import com.shiro.classflow.data.network.wbu.AccessLayer
import com.shiro.classflow.data.network.wbu.suggestedAction
import com.xingheyuzhuan.shiguangschedule.ui.components.shouldOfferDirectOnce
import com.xingheyuzhuan.shiguangschedule.ui.components.shouldOfferWebVpnOnce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一卡通账单页的状态机。
 *
 * 真机事故（2026-11）：校园网内刷新成功 → 切到校外 → 刷新 → 取消 WebVPN 验证码弹窗
 * → 界面显示「本月暂无账单」。用户什么都没做错，是状态机把「没拿到结果」当成了「没有账单」。
 * 这些断言就是钉住那条边界。
 */
class CampusCardBillStateTest {

    private fun entry(id: String = "e1") = LegacyCampusCardEntry(
        id = id,
        occurredAt = "2026-11-03 15:48:00",
        merchant = "U净",
        amount = -0.11,
        transName = "电子账户消费",
        serial = "S1"
    )

    // ---------------- isEmpty：只有「真的加载成功且确实为空」才成立 ----------------

    @Test
    fun `初始加载中的状态不算空`() {
        assertFalse(CampusCardBillUiState().isEmpty)
        assertFalse(CampusCardBillUiState().cancelledWithoutData)
    }

    @Test
    fun `从未成功加载过就绝不能说本月暂无账单`() {
        // 回归：以前这里 isEmpty 为 true，取消一次验证码就渲染成「本月暂无账单」
        val state = CampusCardBillUiState(
            isLoading = false,
            entries = emptyList(),
            errorMessage = null,
            hasLoadedOnce = false
        )
        assertFalse(state.isEmpty)
    }

    @Test
    fun `取消且无数据走中性占位而不是空态`() {
        val state = CampusCardBillUiState(
            isLoading = false,
            entries = emptyList(),
            errorMessage = null,
            hasLoadedOnce = false,
            cancelled = true
        )
        assertTrue(state.cancelledWithoutData)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `加载成功但没有数据才算真的空`() {
        val state = CampusCardBillUiState(
            isLoading = false,
            entries = emptyList(),
            errorMessage = null,
            hasLoadedOnce = true
        )
        assertTrue(state.isEmpty)
        assertFalse(state.cancelledWithoutData)
    }

    @Test
    fun `有数据时既不算空也不算取消占位`() {
        val state = CampusCardBillUiState(
            isLoading = false,
            entries = listOf(entry()),
            hasLoadedOnce = true
        )
        assertFalse(state.isEmpty)
        assertFalse(state.cancelledWithoutData)
    }

    @Test
    fun `有旧数据时取消要保留数据`() {
        // 方案 A：有数据就留在列表里，只在底部标一行「已取消刷新」
        val state = CampusCardBillUiState(
            isLoading = false,
            entries = listOf(entry()),
            hasLoadedOnce = true,
            cancelled = true
        )
        assertEquals(1, state.entries.size)
        assertFalse(state.cancelledWithoutData)
        assertFalse(state.isEmpty)
    }

    @Test
    fun `有错误时既不算空也不算取消占位`() {
        val state = CampusCardBillUiState(
            isLoading = false,
            entries = emptyList(),
            errorMessage = "当前不在校园网，无法直连学校系统",
            hasLoadedOnce = true
        )
        assertFalse(state.isEmpty)
        assertFalse(state.cancelledWithoutData)
    }

    @Test
    fun `刷新过程中不显示空态`() {
        val state = CampusCardBillUiState(
            isLoading = false,
            isRefreshing = true,
            entries = emptyList(),
            hasLoadedOnce = true
        )
        assertFalse(state.isEmpty)
    }

    // ---------------- 「改用 WebVPN」的显示条件 ----------------

    @Test
    fun `直连网络失败才提示改用 WebVPN`() {
        val failure = AccessFailure.Unreachable(AccessLayer.CampusDirect)
        assertTrue(failure.shouldOfferWebVpnOnce(useVpn = false))
        assertEquals(
            com.shiro.classflow.data.network.wbu.AccessAction.UseWebVpnOnce,
            failure.suggestedAction(webVpnEnabled = false)
        )
    }

    @Test
    fun `已经在 WebVPN 上就不再重复提示`() {
        val failure = AccessFailure.Unreachable(AccessLayer.WebVpnPortal)
        assertFalse(failure.shouldOfferWebVpnOnce(useVpn = true))
    }

    @Test
    fun `有旧数据时刷新失败要能看见提示`() {
        // 回归：以前 inline 提示渲染在列表末尾（99 笔数据之后），用户根本滚不到，
        // 等于「刷新失败却什么都不说」。判据必须与列表非空同时成立。
        val failed = CampusCardBillUiState(
            isLoading = false,
            entries = listOf(entry("a"), entry("b")),
            hasLoadedOnce = true,
            errorMessage = "连不上 WebVPN，请检查网络后重试"
        )
        assertTrue(failed.entries.isNotEmpty())
        assertNotNull(failed.errorMessage)
        // 有数据 + 有错误 → 走列表内联提示，而不是整页 ErrorBlock
        assertFalse(failed.isEmpty)

        val cancelled = CampusCardBillUiState(
            isLoading = false,
            entries = listOf(entry("a")),
            hasLoadedOnce = true,
            cancelled = true
        )
        assertTrue(cancelled.entries.isNotEmpty())
        assertTrue(cancelled.cancelled)
        assertFalse(cancelled.cancelledWithoutData)
    }

    @Test
    fun `WebVPN 连不上时给改用直连的出路`() {
        // 真实场景：人已经走回校园网 / 校园 VPN 刚恢复，重试同一条通道没有意义
        val failure = AccessFailure.Unreachable(AccessLayer.WebVpnPortal)
        assertTrue(failure.shouldOfferDirectOnce(useVpn = true))
        assertFalse(failure.shouldOfferWebVpnOnce(useVpn = true))
    }

    @Test
    fun `直连失败时不会同时提示改用直连`() {
        val failure = AccessFailure.Unreachable(AccessLayer.CampusDirect)
        assertFalse(failure.shouldOfferDirectOnce(useVpn = false))
        assertTrue(failure.shouldOfferWebVpnOnce(useVpn = false))
    }

    @Test
    fun `用户取消不该诱导再点一次 WebVPN`() {
        assertFalse(AccessFailure.Cancelled.shouldOfferWebVpnOnce(useVpn = false))
        assertFalse(AccessFailure.Cancelled.shouldOfferWebVpnOnce(useVpn = true))
    }

    @Test
    fun `登录态失效走重新登录而不是 WebVPN`() {
        val failure = AccessFailure.SessionExpired(AccessLayer.UnifiedAuth)
        assertFalse(failure.shouldOfferWebVpnOnce(useVpn = false))
    }

    @Test
    fun `手机没网时换通道解决不了问题`() {
        val failure = AccessFailure.Unreachable(AccessLayer.CampusDirect, offline = true)
        assertFalse(failure.shouldOfferWebVpnOnce(useVpn = false))
    }
}
