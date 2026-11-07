package com.xingheyuzhuan.shiguangschedule

import android.content.Context

/**
 * navigation3 环境下替代 NavController 的窄接口。
 * Screen 组件统一接收此接口，内部不再依赖字符串路由。
 */
interface NavBridge {
    val context: Context

    /** 导航到目标页面（二级页面：入栈） */
    fun navigate(destination: Destination)

    /** 底栏一级页面切换（清空栈后切换，避免残留二级页面栈） */
    fun navigateToMain(destination: Destination)

    /** 用目标页面替换当前栈顶页面（用于「扫码页 → 结果页」这类不保留返回栈的场景） */
    fun replace(destination: Destination)

    /** 返回上一页 */
    fun popBackStack()

    /**
     * 结束当前页，并落到 [destination]。
     *
     * 用于「第三方 H5 / 过渡页退出后必须回到某个本应用页面」的场景（例：扫码进洗衣机
     * H5，退出后要看到洗衣进度与提醒）。与 [replace] 的差别在去重：
     * 如果 [destination] 已经是栈顶下面那一页，就只弹出当前页，不会再叠一份相同的页面 ——
     * 否则用户从洗衣机页扫码出去、退回来时，栈里会出现两个洗衣机页，返回键要多按一次。
     */
    fun finishTo(destination: Destination)

    /** 向上返回（工具栏返回按钮） */
    fun navigateUp()
}
