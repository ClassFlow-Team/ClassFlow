package com.shiro.classflow.ui.webapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 地址里混进空白时的修补（[repairStrayWhitespace]）。
 *
 * 背景（真机 + WebView 调试协议实测）：门户核销票据后给出的跳转目标偶尔是
 * `https://ydmh.wbu.edu.cn/ydmh ?v=1791546995952`（`/ydmh` 与 `?` 之间多一个空格），
 * Chromium 把空格编码成 `%20` 后请求变成 `…/ydmh%20?v=…`，Tomcat 对路径 `/ydmh ` 直接 404，
 * 页面就停在「请求的资源[/ydmh%20]不可用」。这里把这类多余空白抹掉。
 *
 * 注意只修「路径末尾」与「整条地址末尾」：路径**中间**的 `%20` 是合法内容（比如带空格的文件名）。
 */
class WebAppUrlRepairTest {

    @Test
    fun stripsEncodedTrailingSpaceBeforeQuery() {
        assertEquals(
            "https://ydmh.wbu.edu.cn/ydmh?v=1791546995952",
            repairStrayWhitespace("https://ydmh.wbu.edu.cn/ydmh%20?v=1791546995952")
        )
    }

    @Test
    fun stripsLiteralTrailingSpaceBeforeQuery() {
        assertEquals(
            "https://ydmh.wbu.edu.cn/ydmh?v=1",
            repairStrayWhitespace("https://ydmh.wbu.edu.cn/ydmh ?v=1")
        )
    }

    @Test
    fun stripsEncodedTabBeforeQuery() {
        assertEquals(
            "https://ydmh.wbu.edu.cn/ydmh?v=1",
            repairStrayWhitespace("https://ydmh.wbu.edu.cn/ydmh%09?v=1")
        )
    }

    @Test
    fun stripsTrailingWhitespaceWithNoQuery() {
        assertEquals(
            "https://ydmh.wbu.edu.cn/ydmh/",
            repairStrayWhitespace("https://ydmh.wbu.edu.cn/ydmh/%20")
        )
    }

    @Test
    fun keepsCleanUrlsUntouched() {
        assertNull(repairStrayWhitespace("https://ydmh.wbu.edu.cn/ydmh/?v=1"))
        assertNull(repairStrayWhitespace("https://ydmh.wbu.edu.cn/ydmh/html/yd/index.html#/"))
        assertNull(repairStrayWhitespace("https://ydmh.wbu.edu.cn/ydmh/login/cas?ticket=ST-1"))
    }

    @Test
    fun doesNotTouchSpacesInsideThePath() {
        // 路径中间的 %20 是合法内容（带空格的文件名），不能动
        assertNull(repairStrayWhitespace("https://ehall.wbu.edu.cn/files/My%20File.pdf"))
        // 查询里的空白也不动（只在「无查询」时才处理整条地址末尾）
        assertNull(repairStrayWhitespace("https://ydmh.wbu.edu.cn/ydmh/login/cas?ticket=ST-1%20"))
    }

    @Test
    fun ignoresNonUrls() {
        assertNull(repairStrayWhitespace(""))
        assertNull(repairStrayWhitespace("   "))
        assertNull(repairStrayWhitespace("about:blank"))
        assertNull(repairStrayWhitespace("weixin://wap/pay?prepayid=1"))
    }

    // ---------- 服务端把 https 降级成 http 时抬回去 ----------

    @Test
    fun upgradesTheDowngradedPortalRedirect() {
        // 2026-11-02 抓包：核销票据后 302 到 http，而这个 http 口 WAF 上没接入 → 422 站点不存在
        assertEquals(
            "https://ydmh.wbu.edu.cn/ydmh?v=1793588098222",
            upgradeHttpToHttps("http://ydmh.wbu.edu.cn/ydmh?v=1793588098222")
        )
        assertEquals(
            "https://ydmh.wbu.edu.cn/ydmh/html/yd/index.html",
            upgradeHttpToHttps("http://ydmh.wbu.edu.cn/ydmh/html/yd/index.html")
        )
    }

    @Test
    fun leavesHttpsAndNonDefaultPortsAlone() {
        // 已经是 https：没什么可修的
        assertNull(upgradeHttpToHttps("https://ydmh.wbu.edu.cn/ydmh?v=1"))
        // WebVPN 代理宿主是 https:8118，http 版也不该被抬成 https:443（端口是显式的）
        assertNull(upgradeHttpToHttps("http://ydmh-wbu-edu-cn-s.webvpn.wbu.edu.cn:8118/ydmh"))
        assertNull(upgradeHttpToHttps("about:blank"))
    }

    @Test
    fun onlyHttpsOriginsGetUpgraded() {
        // 移动门户 / 图书馆座位：主页是 https → 允许抬回
        assertEquals(
            "https://ydmh.wbu.edu.cn/ydmh",
            upgradeHttpToHttps("http://ydmh.wbu.edu.cn/ydmh").takeIf { prefersHttpsOrigin("https://ydmh.wbu.edu.cn") }
        )
        // 一卡通平台本身就是 http 服务：抬成 https 会直接连不上
        assertNull(upgradeHttpToHttps("http://yktfwpt.wbu.edu.cn/plat").takeIf { prefersHttpsOrigin("http://yktfwpt.wbu.edu.cn") })
    }

    @Test
    fun prefersHttpsOriginReadsTheRecipe() {
        assertEquals(true, prefersHttpsOrigin("https://ydmh.wbu.edu.cn"))
        assertEquals(false, prefersHttpsOrigin("http://yktfwpt.wbu.edu.cn"))
        assertEquals(false, prefersHttpsOrigin(null))
    }
}
