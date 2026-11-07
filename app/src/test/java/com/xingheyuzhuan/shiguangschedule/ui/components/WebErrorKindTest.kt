package com.xingheyuzhuan.shiguangschedule.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页错误页的分类与信息提取（[webErrorKind] / [extractNetErrorName] / [isRealWebLoadFailure]）。
 *
 * 背景：WebView 在「服务器压根没返回内容」时会自己渲染一张 Chromium 的绿色机器人页，
 * 我们要把那次回调接管过来换成应用自己的错误页。分类错了会给出误导性的提示
 * （比如超时被说成「没有联网」），所以这些映射必须有测试兜着。
 *
 * 样例都取自真机 `WebResourceError.description` 的实际格式：`net::ERR_CONNECTION_TIMED_OUT`。
 */
class WebErrorKindTest {

    // ── extractNetErrorName ──

    @Test
    fun extractsChromiumNameFromDescription() {
        assertEquals(
            "ERR_CONNECTION_TIMED_OUT",
            extractNetErrorName("net::ERR_CONNECTION_TIMED_OUT")
        )
    }

    @Test
    fun extractsNameWhenDescriptionCarriesExtraText() {
        assertEquals(
            "ERR_NAME_NOT_RESOLVED",
            extractNetErrorName("net::ERR_NAME_NOT_RESOLVED (could not resolve host)")
        )
    }

    @Test
    fun returnsNullWhenDescriptionHasNoNetError() {
        assertNull(extractNetErrorName(null))
        assertNull(extractNetErrorName(""))
        assertNull(extractNetErrorName("连接失败"))
    }

    // ── webErrorKind：优先按 Chromium 错误名 ──

    @Test
    fun mapsOffline() {
        assertEquals(WebErrorKind.OFFLINE, webErrorKind(-106, "net::ERR_INTERNET_DISCONNECTED"))
        assertEquals(WebErrorKind.OFFLINE, webErrorKind(-1, "net::ERR_ADDRESS_UNREACHABLE"))
    }

    @Test
    fun mapsDnsFailure() {
        assertEquals(WebErrorKind.DNS, webErrorKind(-2, "net::ERR_NAME_NOT_RESOLVED"))
    }

    @Test
    fun mapsConnectionAndTimeoutSeparately() {
        assertEquals(WebErrorKind.CONNECTION, webErrorKind(-6, "net::ERR_CONNECTION_REFUSED"))
        assertEquals(WebErrorKind.CONNECTION, webErrorKind(-6, "net::ERR_CONNECTION_RESET"))
        assertEquals(WebErrorKind.TIMEOUT, webErrorKind(-8, "net::ERR_CONNECTION_TIMED_OUT"))
        assertEquals(WebErrorKind.TIMEOUT, webErrorKind(-7, "net::ERR_TIMED_OUT"))
    }

    @Test
    fun mapsEmptyResponse() {
        // 目标场景：服务器接受了连接却什么都没回，Chromium 落机器人页
        assertEquals(WebErrorKind.EMPTY_RESPONSE, webErrorKind(-324, "net::ERR_EMPTY_RESPONSE"))
        assertEquals(WebErrorKind.EMPTY_RESPONSE, webErrorKind(-1, "net::ERR_CONTENT_LENGTH_MISMATCH"))
    }

    @Test
    fun mapsSslAndCertificateProblems() {
        assertEquals(WebErrorKind.SSL, webErrorKind(-11, "net::ERR_SSL_PROTOCOL_ERROR"))
        assertEquals(WebErrorKind.SSL, webErrorKind(-11, "net::ERR_CERT_COMMON_NAME_INVALID"))
    }

    @Test
    fun mapsUnknownUrlScheme() {
        assertEquals(
            WebErrorKind.UNSUPPORTED_SCHEME,
            webErrorKind(-10, "net::ERR_UNKNOWN_URL_SCHEME")
        )
    }

    @Test
    fun fallsBackToLegacyCodeWhenNoChromiumName() {
        // 没有 description（或里面没有 net::ERR_*）时退回 WebViewClient 的老枚举
        assertEquals(WebErrorKind.DNS, webErrorKind(-2, null))
        assertEquals(WebErrorKind.CONNECTION, webErrorKind(-6, null))
        assertEquals(WebErrorKind.CONNECTION, webErrorKind(-7, null))
        assertEquals(WebErrorKind.TIMEOUT, webErrorKind(-8, null))
        assertEquals(WebErrorKind.UNSUPPORTED_SCHEME, webErrorKind(-10, null))
        assertEquals(WebErrorKind.SSL, webErrorKind(-11, null))
        assertEquals(WebErrorKind.UNKNOWN, webErrorKind(-1, null))
        assertEquals(WebErrorKind.UNKNOWN, webErrorKind(null, null))
    }

    // ── webErrorCodeText ──

    @Test
    fun codeTextPrefersChromiumNameWithLegacyCode() {
        assertEquals(
            "ERR_CONNECTION_TIMED_OUT (-8)",
            webErrorCodeText(-8, "net::ERR_CONNECTION_TIMED_OUT")
        )
    }

    @Test
    fun codeTextFallsBackToLegacyName() {
        assertEquals("ERR_NAME_NOT_RESOLVED (-2)", webErrorCodeText(-2, null))
        assertEquals("ERR_FAILED (-1)", webErrorCodeText(-1, ""))
    }

    @Test
    fun codeTextNeedsAtLeastOneSource() {
        assertNull(webErrorCodeText(null, null))
    }

    @Test
    fun codeTextKeepsUnknownCodeVisible() {
        // 只是没有名字，代码本身还要给用户看（便于反馈）
        assertEquals("ERROR (-999)", webErrorCodeText(-999, null))
    }

    // ── isRealWebLoadFailure ──

    @Test
    fun abortedNavigationIsNotAFailure() {
        assertFalse(isRealWebLoadFailure("https://ydmh.wbu.edu.cn/ydmh", -3))
    }

    @Test
    fun blankPlaceholderPagesAreNotFailures() {
        assertFalse(isRealWebLoadFailure("about:blank", -2))
        assertFalse(isRealWebLoadFailure("data:text/html,hi", -2))
        assertFalse(isRealWebLoadFailure("javascript:void(0)", -2))
    }

    @Test
    fun missingUrlIsNotAFailure() {
        assertFalse(isRealWebLoadFailure(null, -2))
        assertFalse(isRealWebLoadFailure("   ", -2))
    }

    @Test
    fun realNetworkFailureIsReported() {
        assertTrue(isRealWebLoadFailure("https://ydmh.wbu.edu.cn/ydmh", -106))
        assertTrue(isRealWebLoadFailure("https://jwxt.wbu.edu.cn", -8))
    }
}
