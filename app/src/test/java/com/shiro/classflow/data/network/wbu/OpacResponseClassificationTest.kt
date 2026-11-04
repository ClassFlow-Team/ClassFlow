package com.shiro.classflow.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图书馆 OPAC 响应归类单测。
 *
 * 钉住的事故（2026-11 实测）：`reader/book_lst.php`（当前借阅）服务端稳定返回 **HTTP 500**，
 * 而同一会话下 `redr_info.php` / `book_hist.php` 等读者页全部 200。旧逻辑把「非 200」一律
 * 当成会话失效，于是：
 *
 *   进入页面 → 500 被判成「登录过期」 → 静默重登 → 还是 500 → 再弹登录面板 → 死循环。
 *
 * 用户看到的是「一进图书馆就弹登录、登录后还弹」，而真正的原因是学校那台 OPAC 的页面报错。
 * 因此「服务端出错」必须与「没登录」在判据层就分开。
 */
class OpacResponseClassificationTest {

    // ---------- 服务端错误不能被当成登录失效 ----------

    @Test
    fun serverErrorIsServiceErrorNotSessionExpired() {
        assertEquals(
            OpacResponseKind.SERVICE_ERROR,
            classifyOpacResponse(500, null, "opac.wbu.edu.cn")
        )
        assertEquals(
            OpacResponseKind.SERVICE_ERROR,
            classifyOpacResponse(502, null, "opac.wbu.edu.cn")
        )
        assertEquals(
            OpacResponseKind.SERVICE_ERROR,
            classifyOpacResponse(404, null, "opac.wbu.edu.cn")
        )
    }

    @Test
    fun serverErrorWithLocationStillCountsAsServiceError() {
        // 有些反代出错时会同时给 500 + Location；状态码才是判据
        assertEquals(
            OpacResponseKind.SERVICE_ERROR,
            classifyOpacResponse(500, "https://opac.wbu.edu.cn/reader/redr_info.php", "opac.wbu.edu.cn")
        )
    }

    @Test
    fun serviceErrorNeverAsksForRelogin() {
        // 这条是用户实际感受到的差别：不弹登录框
        val failure = WbuFailureDetector.fromThrowable(WbuServiceErrorException(500), AccessLayer.Service)
        assertFalse("服务端 5xx 不该要求重新登录", failure.needsRelogin)
    }

    // ---------- 真正的会话失效仍要认出来 ----------

    @Test
    fun redirectToLoginPageIsSessionExpired() {
        assertEquals(
            OpacResponseKind.SESSION_EXPIRED,
            classifyOpacResponse(302, "https://opac.wbu.edu.cn/reader/login.php", "opac.wbu.edu.cn")
        )
    }

    @Test
    fun gatewayTakeoverIsSessionExpired() {
        // WebVPN 门禁（TWFID）失效时，Sangfor 把请求接到门户本身
        assertEquals(
            OpacResponseKind.SESSION_EXPIRED,
            classifyOpacResponse(200, null, "webvpn.wbu.edu.cn")
        )
        assertEquals(
            OpacResponseKind.SESSION_EXPIRED,
            classifyOpacResponse(200, "https://webvpn.wbu.edu.cn/por/login_psw.csp", "opac.wbu.edu.cn")
        )
    }

    @Test
    fun gatewayTakeoverIsRecognisedEvenWithErrorStatus() {
        // 被门户接管优先判：那是登录问题，不是「服务端故障」
        assertEquals(
            OpacResponseKind.SESSION_EXPIRED,
            classifyOpacResponse(302, "https://webvpn.wbu.edu.cn/por/login_psw.csp", "opac.wbu.edu.cn")
        )
    }

    @Test
    fun sessionExpiredStillAsksForRelogin() {
        val failure = WbuFailureDetector.fromThrowable(
            WbuSessionExpiredException(AccessLayer.Service),
            AccessLayer.Service
        )
        assertTrue(failure.needsRelogin)
    }

    // ---------- 正常页面 ----------

    @Test
    fun normalPageIsOk() {
        assertEquals(
            OpacResponseKind.OK,
            classifyOpacResponse(200, null, "opac.wbu.edu.cn")
        )
    }

    @Test
    fun proxySubdomainIsNotAGateway() {
        // WebVPN 正常访问时落点就是代理子域，必须精确比较 host 而不是 substring
        assertEquals(
            OpacResponseKind.OK,
            classifyOpacResponse(200, null, "opac-wbu-edu-cn-s.webvpn.wbu.edu.cn")
        )
    }

    @Test
    fun redirectToAnotherReaderPageIsNotSessionExpired() {
        // 读者页之间的普通跳转（如 hwthau.php → redr_info.php）不算掉登录
        assertEquals(
            OpacResponseKind.SERVICE_ERROR,
            classifyOpacResponse(302, "https://opac.wbu.edu.cn/reader/redr_info.php", "opac.wbu.edu.cn")
        )
    }
}
