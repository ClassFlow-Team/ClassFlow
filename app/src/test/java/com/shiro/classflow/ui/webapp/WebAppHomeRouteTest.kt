package com.shiro.classflow.ui.webapp

import com.shiro.classflow.data.model.wbu.WebAppCatalog
import com.shiro.classflow.data.model.wbu.WebAppDefinition
import com.shiro.classflow.data.model.wbu.WebAppId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回键的「首页」判定（[isAtHomeRoute]）。
 *
 * 背景：门户里点开的应用（ehall / 新生入馆教育 / 超星 …）都是**站外页面**，
 * 它们大多没有 hash 路由 —— 早先按「hash 为空 = 首页」一刀切判定，于是站在入馆教育页面里
 * 按返回键会直接退出整个 WebApp 容器（真机反馈的问题）。
 *
 * 现在的口径：只有落在**本应用自己的宿主**（真实宿主 / 它的 WebVPN 代理宿主）上的页面才谈得上
 * 「首页」，站外页面一律照常回退历史。
 */
class WebAppHomeRouteTest {

    private val portal: WebAppDefinition = WebAppCatalog.MOBILE_PORTAL

    /** 门户自己的宿主 + 它在 WebVPN 下的代理宿主。 */
    private val portalOwnHosts = setOf("ydmh.wbu.edu.cn", "ydmh-wbu-edu-cn-s.webvpn.wbu.edu.cn")

    @Test
    fun thirdPartyAppInsidePortalIsNotHome() {
        // 新生入馆教育（zxst）与 ehall 应用：站外页面，hash 为空也不算首页
        assertFalse(
            isAtHomeRoute(
                "http://zxst.wbu.edu.cn:6060/Web/CASDefault?ticket=ST-1",
                portal,
                portalOwnHosts
            )
        )
        assertFalse(
            isAtHomeRoute(
                "http://ehall.wbu.edu.cn/xsfwdt/sys/swmxsqjapp/*default/index.do?ticket=ST-2",
                portal,
                portalOwnHosts
            )
        )
        // WebVPN 下站外页面同样是 *.webvpn.wbu.edu.cn，但不能被认成门户自己的页面
        assertFalse(
            isAtHomeRoute(
                "http://ehall-wbu-edu-cn.webvpn.wbu.edu.cn:8118/xsfwdt/index.do",
                portal,
                portalOwnHosts
            )
        )
    }

    @Test
    fun portalOwnHomeCountsAsHome() {
        // 刚加载完：`…/index.html#/`（hash 就是 `#/`）
        assertTrue(isAtHomeRoute("https://ydmh.wbu.edu.cn/ydmh/html/yd/index.html#/", portal, portalOwnHosts))
        // tabbar 首页
        assertTrue(
            isAtHomeRoute(
                "https://ydmh.wbu.edu.cn/ydmh/html/yd/index.html#/pages/home/index",
                portal,
                portalOwnHosts
            )
        )
        // WebVPN 代理宿主上的首页
        assertTrue(
            isAtHomeRoute(
                "http://ydmh-wbu-edu-cn-s.webvpn.wbu.edu.cn:8118/ydmh/html/yd/index.html#/",
                portal,
                portalOwnHosts
            )
        )
    }

    @Test
    fun otherPortalPagesAreNotHome() {
        assertFalse(
            isAtHomeRoute(
                "https://ydmh.wbu.edu.cn/ydmh/html/yd/index.html#/pages/message/index",
                portal,
                portalOwnHosts
            )
        )
    }

    @Test
    fun otherDefinitionsKeepTheirOwnMarkers() {
        val card = WebAppCatalog.CAMPUS_CARD
        val cardHosts = setOf("yktfwpt.wbu.edu.cn", "yktfwpt-wbu-edu-cn.webvpn.wbu.edu.cn")
        // 一卡通平台没开「hash 为空即首页」：历史路由的内页照样算内页
        assertFalse(isAtHomeRoute("http://yktfwpt.wbu.edu.cn/plat/campusCode?synjones-auth=x", card, cardHosts))
        assertTrue(isAtHomeRoute("http://yktfwpt.wbu.edu.cn/plat/shouyeUser", card, cardHosts))
        // 站外的第三方 H5（U净）也不是它的首页
        assertFalse(isAtHomeRoute("https://h5.ujing.com.cn/home#/home", card, cardHosts))
    }

    @Test
    fun blankPagesCountAsHome() {
        assertTrue(isAtHomeRoute("", portal, portalOwnHosts))
        assertTrue(isAtHomeRoute("about:blank", portal, portalOwnHosts))
    }
}
