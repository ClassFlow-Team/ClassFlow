package com.shiro.classflow.ui.campus.ujing

/**
 * 取水页**离开又回来**时的判定。
 *
 * 这条规则不是「顺手优化的细节」，而是真机上踩出来的：它决定页面会不会在用户
 * 去充了个值回来之后自己悄悄消失。代价发生在用户的钱和耐心上，
 * 所以从 Compose 里抽出来，靠单测钉住。
 */

/**
 * 这个取水页是不是「系统从保存状态恢复出来的、已经没救的旧页面」。
 *
 * 背景：订单信息（订单号、取水点）只活在 ViewModel 内存里，不落盘。进程被杀之后系统恢复
 * 这一页时，页面手里什么都没有 —— 显示「正在连接饮水机…」是骗人，重走下单流程更糟。
 * 所以这种页面什么都不画，直接退出去。
 *
 * 难点全在**怎么认出它**。四个信号缺一不可：
 * - [handledInSavedState]：这个导航条目之前确实被处理过（`rememberSaveable` 跨进程存活）。
 *   没有它的话，一次正常的全新进入也会被判成「恢复页」然后立刻退出。
 * - [handledInProcess]：本进程自己受理过它（`remember`，只在内存里）。为 true 就是活的。
 * - [viewModelHasOrder]：ViewModel 手里还攥着这一单。**这一项是修 bug 加的**：
 *   页面被别的页面盖住（点「去充值」、看账单）再回来时，composition 会重建、
 *   `remember` 会被清掉，但 ViewModel 按导航条目存活、订单也还在。
 *   少了这项判断，用户充完值回来看到的是页面自己悄悄消失、落回课表 ——
 *   这正是 2026-11-07 真机复现的那个问题。
 * - [hasResumableSession]：进程级兜底，另一种「还活着」的形式。
 *
 * 注意它**只管「页面还该不该显示」**，不碰钱：从充值页回来不会自动补缴，
 * 欠费提示与「立即补缴」原样留着，由用户自己决定什么时候扣这笔钱。
 */
internal fun isDeadRestoredWaterPage(
    handledInProcess: Boolean,
    handledInSavedState: Boolean,
    viewModelHasOrder: Boolean,
    hasResumableSession: Boolean,
): Boolean {
    if (!handledInSavedState) return false      // 全新进入：正常走流程
    if (handledInProcess) return false          // 本进程受理过：活的
    if (viewModelHasOrder) return false         // 被别的页面盖过又回来：订单还在
    if (hasResumableSession) return false       // 进程级会话还在：接着显示
    return true
}
