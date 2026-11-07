package com.shiro.classflow.ui.campus.ujing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从充值页回来」的页面判定（见 `UjingWaterReturnPolicy.kt`）。
 *
 * 这是真机上踩出来的 bug，且代价明确：用户充完值回来，页面把**自己**当成
 * 「进程重启后恢复出来的死页面」直接 `popBackStack()` —— 于是他从充值页按一次返回
 * 就落到课表，接水页消失、欠费也没机会再结。
 *
 * 触发条件很隐蔽：页面被别的页面盖住再回来时 composition 会重建，`remember` 丢了
 * （`handledInProcess=false`），而 `rememberSaveable` 还在（`handledInSavedState=true`）。
 * 这个组合和「进程被杀后系统恢复」长得一模一样，所以必须靠 ViewModel 是否还持有订单来区分。
 */
class UjingWaterReturnPolicyTest {

    @Test
    fun `全新进入不是恢复页`() {
        // 一进来 handledInSavedState 还是 false：什么都不能判，正常走流程
        assertFalse(
            isDeadRestoredWaterPage(
                handledInProcess = false,
                handledInSavedState = false,
                viewModelHasOrder = false,
                hasResumableSession = false,
            )
        )
    }

    /**
     * 实测 bug 的防回归：点「去充值」→ 充值 → 点「完成」→ 返回。
     *
     * 这时 composition 是重建的（`remember` 丢），但 ViewModel 手里还攥着这一单。
     * 判成「死页面」的后果就是页面自己消失、用户落在课表上 —— 账单永远结不掉。
     */
    @Test
    fun `被充值页盖过再回来不是恢复页`() {
        assertFalse(
            isDeadRestoredWaterPage(
                handledInProcess = false,   // remember 丢了
                handledInSavedState = true, // rememberSaveable 还在
                viewModelHasOrder = true,   // ← 关键：ViewModel 说这一单还在
                hasResumableSession = false,
            )
        )
    }

    @Test
    fun `真进程重建才算恢复页`() {
        // 进程死了 → 全新 ViewModel（没订单）、没有可续的会话，这页显示什么都是错的
        assertTrue(
            isDeadRestoredWaterPage(
                handledInProcess = false,
                handledInSavedState = true,
                viewModelHasOrder = false,
                hasResumableSession = false,
            )
        )
    }

    @Test
    fun `本进程受理过就是活的`() {
        assertFalse(
            isDeadRestoredWaterPage(
                handledInProcess = true,
                handledInSavedState = true,
                viewModelHasOrder = false,
                hasResumableSession = false,
            )
        )
    }

    @Test
    fun `进程级可续会话也算活着`() {
        assertFalse(
            isDeadRestoredWaterPage(
                handledInProcess = false,
                handledInSavedState = true,
                viewModelHasOrder = false,
                hasResumableSession = true,
            )
        )
    }
}
