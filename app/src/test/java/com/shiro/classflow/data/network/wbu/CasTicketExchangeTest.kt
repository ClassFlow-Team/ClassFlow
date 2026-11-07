package com.shiro.classflow.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CasTicketExchange` 纯函数部分的单测。
 *
 * 这里钉的是 2026-11-08 那次校外故障的**判据**，不是网络行为（本仓库没有 MockWebServer /
 * Robolectric，也不该为这一个判据引进来）：
 *
 * 1. **内容走哪条通道，换票就走哪条通道。** 过去 `transport.idsBase()` 只看
 *    「统一认证经过 WebVPN」这个设置项（默认关），于是「内容走隧道、换票走公网」，
 *    校外换票那一跳被云 WAF 回 `403` 挡板页，移动门户 / 图书借阅 / 座位预约全军覆没。
 * 2. **内容直连时不自动切隧道。** 票据宿主必须与内容通道一致；`ids-wbu-edu-cn.webvpn.wbu.edu.cn`
 *    是两级代理子域，直连 transport 连它的 TLS 主机名都验不过；而且静默换通道会把
 *    「网线 / 校园网不通」这类真实原因掩盖成一次莫名的成功。直连失败时 UI 有
 *    「改用 WebVPN」按钮（`AccessAction.UseWebVpnOnce`），交给用户决定。
 * 3. **不把 `login` 规范化成 `login/`。** 公网 `login/` 是 `403` 挡板页，隧道 `login/` 是 `404`；
 *    只有 `/authserver/login?service=…` 这种形态两边都认。
 */
class CasTicketExchangeTest {

    private val publicBase = "http://ids.wbu.edu.cn"
    private val proxyBase = "http://ids-wbu-edu-cn.webvpn.wbu.edu.cn:8118"

    // ------------------- ticketBaseFor -------------------

    @Test
    fun contentDirectAndSettingOffUsesPublicBase() {
        assertEquals(
            publicBase,
            CasTicketExchange.ticketBaseFor(
                publicBase = publicBase,
                proxyBase = proxyBase,
                contentViaVpn = false,
                idsViaWebVpn = false,
            )
        )
    }

    @Test
    fun contentDirectWithExplicitSettingUsesProxyBase() {
        // 用户显式打开「统一认证经过 WebVPN」：尊重它，即使内容走直连
        assertEquals(
            proxyBase,
            CasTicketExchange.ticketBaseFor(
                publicBase = publicBase,
                proxyBase = proxyBase,
                contentViaVpn = false,
                idsViaWebVpn = true,
            )
        )
    }

    @Test
    fun contentTunnelWithSettingOffUsesProxyBase() {
        // 本次事故那一格：内容明明走隧道，换票却打公网 → 校外必撞 403
        assertEquals(
            proxyBase,
            CasTicketExchange.ticketBaseFor(
                publicBase = publicBase,
                proxyBase = proxyBase,
                contentViaVpn = true,
                idsViaWebVpn = false,
            )
        )
    }

    @Test
    fun contentTunnelWithSettingOnUsesProxyBase() {
        assertEquals(
            proxyBase,
            CasTicketExchange.ticketBaseFor(
                publicBase = publicBase,
                proxyBase = proxyBase,
                contentViaVpn = true,
                idsViaWebVpn = true,
            )
        )
    }

    @Test
    fun contentDirectNeverFallsBackToTunnel() {
        // 内容直连 + 什么设置都没有：必须落在公网，不允许背用户偷偷切隧道
        // （切了会让票据宿主与内容通道不一致，两级代理子域的 TLS 主机名也验不过）
        assertEquals(
            publicBase,
            CasTicketExchange.ticketBaseFor(
                publicBase = publicBase,
                proxyBase = proxyBase,
                contentViaVpn = false,
                idsViaWebVpn = false,
            )
        )
    }

    // ------------------- casLoginUrlFor -------------------

    @Test
    fun loginUrlHasNoTrailingSlash() {
        val url = CasTicketExchange.casLoginUrlFor(publicBase, "https://ydmh.wbu.edu.cn/ydmh/login/cas")
        // 带尾斜杠在公网是 403 挡板页、在隧道是 404，只有这种形态两边都认
        assertTrue(url.startsWith("$publicBase/authserver/login?service="))
        assertFalse(url.contains("/authserver/login/"))
    }

    @Test
    fun loginUrlToleratesBaseTrailingSlash() {
        val url = CasTicketExchange.casLoginUrlFor("$publicBase/", "https://opac.wbu.edu.cn/reader/hwthau.php")
        assertFalse(url.contains("//authserver"))
        assertTrue(url.startsWith("$publicBase/authserver/login?service="))
    }

    @Test
    fun serviceIsEncodedExactlyOnce() {
        val url = CasTicketExchange.casLoginUrlFor(proxyBase, "https://opac.wbu.edu.cn/reader/hwthau.php")
        assertTrue(url.contains("%3A%2F%2F"))
        // 二次编码会是 %253A%252F%252F —— CAS 会当成不匹配的 service 直接报错
        assertFalse(url.contains("%253A"))
    }

    @Test
    fun serviceQuerySeparatorsAreEncoded() {
        val url = CasTicketExchange.casLoginUrlFor(publicBase, "https://x.wbu.edu.cn/y?a=1&b=2#frag")
        // `service=` 的取值里不能有裸 `&` / `#`，否则会被解析成额外的查询参数
        val serviceValue = url.substringAfter("service=")
        assertFalse(serviceValue.contains("&"))
        assertFalse(serviceValue.contains("#"))
    }

    // ------------------- ticketLanding -------------------

    @Test
    fun landingWithTicketIsReturnedAsIs() {
        val landing = "http://ydmh.wbu.edu.cn/ydmh/login/cas?ticket=ST-1-abc"
        assertEquals(landing, CasTicketExchange.ticketLanding(landing))
    }

    @Test
    fun trailingWhitespaceIsTrimmed() {
        val landing = "http://ydmh.wbu.edu.cn/ydmh/login/cas?ticket=ST-1-abc  "
        assertEquals(
            "http://ydmh.wbu.edu.cn/ydmh/login/cas?ticket=ST-1-abc",
            CasTicketExchange.ticketLanding(landing)
        )
    }

    @Test
    fun redirectToTrailingSlashLoginHasNoTicket() {
        // 公网那条 301 的 Location：把 `login?service=` 规范成 `login/?service=`，里面没有票据
        assertNull(
            CasTicketExchange.ticketLanding(
                "http://ids.wbu.edu.cn/authserver/login/?service=https%3A%2F%2Fydmh.wbu.edu.cn%2Fydmh%2Flogin%2Fcas"
            )
        )
    }

    @Test
    fun serviceOnlyRedirectHasNoTicket() {
        // 防「子串误判」：只有 `service=` 而已
        assertNull(CasTicketExchange.ticketLanding("http://ids.wbu.edu.cn/authserver/login?service=x"))
    }

    @Test
    fun blankLocationsHaveNoTicket() {
        assertNull(CasTicketExchange.ticketLanding(null))
        assertNull(CasTicketExchange.ticketLanding(""))
        assertNull(CasTicketExchange.ticketLanding("   "))
    }

    @Test
    fun nonHttpLocationIsRejected() {
        // 不是正经网页（相对路径 / 奇异 scheme）不能当落地地址交给 WebView
        assertNull(CasTicketExchange.ticketLanding("/ydmh/login/cas?ticket=ST-1"))
        assertNull(CasTicketExchange.ticketLanding("javascript:alert(1)?ticket=ST-1"))
    }
}
