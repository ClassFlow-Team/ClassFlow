package com.shiro.classflow

import com.shiro.classflow.data.model.wbu.CampusDeviceKind
import com.xingheyuzhuan.shiguangschedule.Destination
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「退出洗衣机 H5 后落到洗衣机页」的栈操作（[finishToDestination]）。
 *
 * 这是导航层最容易被改坏、又最不容易被发现的一段：真机上要点完扫码、下单、返回
 * 才能看到结果。三条路径都要固定住 —— 扫码入口、洗衣机页自己扫码、只带着 H5 一条。
 */
class NavFinishToTest {

    private val washerHub = Destination.CampusDeviceHub(CampusDeviceKind.WASHER.name)
    private val webApp = Destination.WebApp(
        appId = "CAMPUS_CARD",
        pendingAutoScan = "http://app.littleswan.com/u_download.html?type=Ujing&uuid=abc"
    )

    @Test
    fun scanFromCourseScheduleLandsOnWasherHub() {
        // 桌面快捷方式扫一扫：扫码页被 H5 替换，栈里只有课表 + H5
        val stack = mutableListOf<Destination>(Destination.CourseSchedule, webApp)

        stack.finishToDestination(washerHub)

        assertEquals(listOf(Destination.CourseSchedule, washerHub), stack)
    }

    @Test
    fun scanFromWasherHubDoesNotStackTwoHubs() {
        // 洗衣机页里点「扫码」：… → 洗衣机页 → 扫码页（被替换成 H5）→ 退出
        val stack = mutableListOf<Destination>(
            Destination.CourseSchedule,
            washerHub,
            webApp
        )

        stack.finishToDestination(washerHub)

        // 只剩一份洗衣机页，返回键一步就能回到课表
        assertEquals(listOf(Destination.CourseSchedule, washerHub), stack)
    }

    @Test
    fun otherDeviceHubIsNotCollapsed() {
        // 从饮水机页扫码进洗衣机：应该回到洗衣机页，而不是把饮水机页当成落点
        val waterHub = Destination.CampusDeviceHub(CampusDeviceKind.WATER.name)
        val stack = mutableListOf<Destination>(Destination.CourseSchedule, waterHub, webApp)

        stack.finishToDestination(washerHub)

        assertEquals(listOf(Destination.CourseSchedule, waterHub, washerHub), stack)
    }

    @Test
    fun lastOnlyPageStillGetsHub() {
        // 极端情况：栈里只剩被替换后的 H5（例如冷启动直达）
        val stack = mutableListOf<Destination>(webApp)

        stack.finishToDestination(washerHub)

        // 不会留下空栈（那样 NavDisplay 没有页面可显示）
        assertEquals(listOf<Destination>(washerHub), stack)
    }
}
