package com.shiro.classflow.data.network.wbu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAF 拦截页判据的单测。
 *
 * 背景（2026-10-27 抓包实测）：校外直连 `ids.wbu.edu.cn/authserver/oauth2.0/authorize` 时，
 * 回来的不是 CAS 的响应，而是一张 **404** 的拦截页，页面来自 `error.websaas.cn`
 * / `custompages.websaas.cn`。
 *
 * 关键反证 —— 源站的错误页是**原样透传**的，不带这张挡板页：
 * `/no-such-app/xyz` → nginx 原生 404、`/authserver/oauth2.0/nope` → CAS 后端的 JSON 404、
 * `/authserver/a` → 未登录时 JSON 404、带会话时 CAS 自己的 HTML 404。所以抓到的那张 404
 * 不是「源站报错被换了张脸」，而是 WAF 自己的挡板页 —— 这条判据针对的正是它。
 *
 * 两个方向都有代价：放宽了会把普通 4xx 说成「被拦截」（用户白折腾），
 * 收紧了又会让用户看到「页面不存在」这种假原因。所以「源站自己的 404」必须保持不被识别。
 */
class WafBlockDetectorTest {

    private val websaasPage = """
        <!DOCTYPE html>
        <html lang="zh-cn"><head><title>404</title></head>
        <body>
          <div class="wrap"><img src="https://error.websaas.cn/img/404s.png"></div>
          <script src="https://custompages.websaas.cn/sha1.min.js"></script>
          <script>xhr1.setRequestHeader('XWD-Token', 'waduxawe124asdx');</script>
        </body></html>
    """.trimIndent()

    @Test
    fun websaasErrorPageIsRecognised() {
        // 实测形态：HTTP 404 + text/html + websaas 的错误页
        assertTrue(WafBlockDetector.looksLikeWafBlock(404, "text/html", websaasPage))
        // 换一个错误码也要认（同一张自定义页可能配在 403 / 500 上）
        assertTrue(WafBlockDetector.looksLikeWafBlock(403, "text/html;charset=UTF-8", websaasPage))
    }

    @Test
    fun normalResponsesAreNotBlocked() {
        // 状态码是 200 的正常页面绝不能判成拦截：脚本里完全可能提到这些词
        assertFalse(WafBlockDetector.looksLikeWafBlock(200, "text/html", websaasPage))
        // nginx 自己的 404（实测 `/no-such-app/xyz` 就是它）
        assertFalse(
            WafBlockDetector.looksLikeWafBlock(
                404,
                "text/html",
                "<html><head><title>404 Not Found</title></head><body><center><h1>404 Not Found</h1></center><hr><center>nginx</center></body></html>"
            )
        )
        // CAS 后端自己的 HTML 404（登录态正常时 `/authserver/a` 就是它）：源站错误页不换皮，
        // 所以它必须**不被**当成拦截页 —— 这是「WAF 换皮论」已被推翻后的防回归点
        assertFalse(
            WafBlockDetector.looksLikeWafBlock(
                404,
                "text/html;charset=UTF-8",
                "<!DOCTYPE html><html lang=\"en\"><head><title>Whitelabel Error Page</title></head>" +
                    "<body><h1>Whitelabel Error Page</h1><div>There was an unexpected error " +
                    "(type=Not Found, status=404).</div></body></html>"
            )
        )
        // CAS 后端自己的 JSON 404（实测 `/authserver/oauth2.0/nope`）
        assertFalse(
            WafBlockDetector.looksLikeWafBlock(
                404,
                "application/json",
                """{"timestamp":1793512044737,"status":404,"error":"Not Found","message":"No message available"}"""
            )
        )
        // 空响应体
        assertFalse(WafBlockDetector.looksLikeWafBlock(502, "text/html", null))
        assertFalse(WafBlockDetector.looksLikeWafBlock(502, "text/html", "  "))
    }

    @Test
    fun genericBlockPagesAreRecognised() {
        // 换厂商 / 换模板时的通用文案，避免只认一家
        assertTrue(WafBlockDetector.looksLikeWafBlock(403, "text/html", "<h1>Access Denied</h1>"))
        assertTrue(WafBlockDetector.looksLikeWafBlock(403, "text/html", "<p>您的访问被拦截</p>"))
        assertTrue(WafBlockDetector.looksLikeWafBlock(405, "text/html", "<p>This request was blocked</p>"))
    }

    @Test
    fun contentIsCheckedBeforeHtmlAssumption() {
        // 非 HTML 的错误体（JSON / 纯文本）交给各自的业务判据，不当成拦截页
        assertFalse(WafBlockDetector.looksLikeWafBlock(403, "application/json", """{"msg":"access denied"}"""))
    }

    // ---------- 头结构不是判据：源站 nginx 404 与挡板页的头完全一样 ----------

    @Test
    fun originNginx404MustNotBeTreatedAsBlock() {
        // 2026-11-02 实测（经代理回源）：404 / text/html（无 charset）/ 162 B nginx 页，
        // 同样没有 Set-Cookie、没有 X-Content-Type-Options —— 头形态与挡板页一模一样。
        // 所以判据只能看正文；这条用例钉住「路径不存在 ≠ 被拦截」。
        val nginx404 = "<html>\r\n<head><title>404 Not Found</title></head>\r\n<body bgcolor=\"white\">\r\n" +
            "<center><h1>404 Not Found</h1></center>\r\n<hr><center>nginx</center>\r\n</body>\r\n</html>\r\n"
        assertFalse(WafBlockDetector.looksLikeWafBlock(404, "text/html", nginx404))
    }

    @Test
    fun originMarkersOnlyCauseAFastPass() {
        // 带源站标记 → 拦截器直接放过、不读正文（CAS 的 route Cookie 与 nosniff）
        assertTrue(looksLikeOriginResponse("route=3b49aee9699e54a80d6bc9354c5e8f05; Path=/authserver", null))
        assertTrue(looksLikeOriginResponse(null, "nosniff"))
        // 两者都没有：不能据此下结论（nginx 404 就是这种），要读正文
        assertFalse(looksLikeOriginResponse(null, null))
        assertFalse(looksLikeOriginResponse("", "  "))
    }
}
