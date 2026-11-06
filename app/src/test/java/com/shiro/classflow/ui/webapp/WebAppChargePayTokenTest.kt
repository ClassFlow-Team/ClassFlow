package com.shiro.classflow.ui.webapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缴费页（`/charge-app/`）「服务大厅未授权(1)」的修复回归。
 *
 * 根因（真机 + 服务端直连实测）：这个提示就是 `blade-pay/pay` 的
 * `401 {"code":4030,"message":"服务大厅未授权(1)"}`，触发条件只有一个 ——
 * 请求头 `synjones-auth` 后面没有有效令牌。四种形态都复现成 4030：
 * 空值、整个头缺失、`bearer bearer <jwt>` 双重前缀、令牌被篡改。
 *
 * 令牌丢失链路：`charge-app` 的 `getToken()` 把地址栏里的令牌塞进 Vuex 后立刻
 * `history.replaceState` 抹掉 query；此后任何一个接口返回 401，页面响应拦截器
 * 会 `LogoutAction`，而该 mutation 里有 `sessionStorage.clear()` —— 令牌永久丢失。
 * 请求拦截器仍机械地拼 `"bearer " + store.token`，拼出来是 `bearer ` 或
 * `bearer null`，缴费请求于是被 4030 拒掉；而 `ge()` 的 catch 只 console.error、
 * 不调失败回调，按钮 loading 停不下来，看起来像「卡住」。
 *
 * 修复分两部分，这里分别钉住：
 * 1. [isCampusCardChargeAppUrl] —— 令牌兜底脚本只装到缴费 SPA 上；
 * 2. [buildChargeAppPayTokenJs] —— 脚本内容的关键约束（幂等、只补缴费接口、
 *    规范化前缀、不碰 Authorization）。
 */
class WebAppChargePayTokenTest {

    // ---------- isCampusCardChargeAppUrl ----------

    @Test
    fun chargeAppUrlIsTarget() {
        assertTrue(isCampusCardChargeAppUrl("http://yktfwpt.wbu.edu.cn/charge-app/"))
        assertTrue(
            isCampusCardChargeAppUrl(
                "http://yktfwpt.wbu.edu.cn/charge-app/?name=pays&appsourse=ydfwpt&id=412&token=abc"
            )
        )
        assertTrue(isCampusCardChargeAppUrl("http://yktfwpt.wbu.edu.cn/charge-app/#/pays?id=412"))
    }

    @Test
    fun bladePayApiIsNotChargeAppPage() {
        // /blade-pay 是接口前缀，不是页面：脚本只装页面，不装接口
        assertFalse(isCampusCardChargeAppUrl("http://yktfwpt.wbu.edu.cn/blade-pay/pay"))
    }

    @Test
    fun otherPlatformPagesAreNotTargets() {
        assertFalse(isCampusCardChargeAppUrl("http://yktfwpt.wbu.edu.cn/plat/?name=Profile"))
        assertFalse(isCampusCardChargeAppUrl("http://yktfwpt.wbu.edu.cn/campus-card/?name=cardRecharge"))
        assertFalse(isCampusCardChargeAppUrl("http://yktfwpt.wbu.edu.cn/charge/feeitem/toAppitem?feeitemid=412"))
    }

    @Test
    fun foreignHostIsNotTarget() {
        assertFalse(isCampusCardChargeAppUrl("http://example.com/charge-app/"))
        assertFalse(isCampusCardChargeAppUrl("https://yktfwpt.wbu.edu.cn.evil.com/charge-app/"))
    }

    @Test
    fun blankAndMalformedUrlsAreNotTargets() {
        assertFalse(isCampusCardChargeAppUrl(null))
        assertFalse(isCampusCardChargeAppUrl(""))
        assertFalse(isCampusCardChargeAppUrl("   "))
        assertFalse(isCampusCardChargeAppUrl("not a url \u0020:%"))
    }

    // ---------- buildChargeAppPayTokenJs ----------

    @Test
    fun scriptBakesTokenAsQuotedLiteral() {
        val js = buildChargeAppPayTokenJs("eyJhbGciOiJIUzI1NiJ9.payload.sig")
        // JSONObject.quote 会带上双引号，脚本里直接是字符串字面量
        assertTrue(js.contains("\"eyJhbGciOiJIUzI1NiJ9.payload.sig\""))
        assertFalse(js.contains("__CF_PAY_TOKEN_LITERAL__"))
    }

    @Test
    fun scriptHandlesBlankTokenWithoutBreaking() {
        // platformToken 为空（还没换到票）时也必须产出可执行脚本，不能出现裸的占位符
        val js = buildChargeAppPayTokenJs(null)
        assertTrue(js.contains("\"\""))
        assertFalse(js.contains("__CF_PAY_TOKEN_LITERAL__"))
        assertTrue(buildChargeAppPayTokenJs("   ").contains("\"   \""))
    }

    @Test
    fun scriptIsIdempotent() {
        // 每次 onPageStarted / onPageFinished / doUpdateVisitedHistory 都会注入，
        // 必须靠哨兵变量保证只包装一次 XHR（否则包装会层层叠加）
        val js = buildChargeAppPayTokenJs("t")
        assertTrue(js.contains("__cfPayTokenHook"))
        assertTrue(js.contains("if (window.__cfPayTokenHook) return;"))
    }

    @Test
    fun scriptOnlyTouchesPayEndpoints() {
        val js = buildChargeAppPayTokenJs("t")
        assertTrue(js.contains("/blade-pay/"))
        assertTrue(js.contains("/blade-auth/"))
        // 补头范围必须锁死在缴费接口，别去动 /charge/user/... 这类页面自己的鉴权请求
        assertTrue(js.contains("isPayUrl"))
    }

    @Test
    fun scriptNeverForgetsTheBearerPrefix() {
        // 服务端要求 "bearer <jwt>"；缺前缀返回 401 "请求未授权"，
        // 双重前缀返回 4030 "服务大厅未授权(1)"。脚本必须统一规范化成单前缀。
        val js = buildChargeAppPayTokenJs("t")
        assertTrue(js.contains("'bearer ' +"))
        assertTrue(js.contains("bearer\\s+"))
    }

    @Test
    fun scriptTreatsNullAndUndefinedTokensAsMissing() {
        // store 被清空后拦截器会拼出 "bearer null" / "bearer undefined"，
        // 这两种值非空但同样会被服务端判成 4030，必须当作「没有令牌」
        val js = buildChargeAppPayTokenJs("t")
        assertTrue(js.contains("null"))
        assertTrue(js.contains("undefined"))
    }

    @Test
    fun scriptDoesNotForgeBasicAuthorization() {
        // Authorization: Basic ... 由页面按 systemType=pay 自己加；
        // 我们重复加反而可能和页面冲突，因此脚本里不出现这段 Basic 值
        val js = buildChargeAppPayTokenJs("t")
        assertFalse(js.contains("Y2hhcmdlOmNoYXJnZV9zZWNyZXQ="))
        assertFalse(js.contains("Authorization"))
    }

    @Test
    fun scriptNeverSetsAuthHeaderTwice() {
        // 2026-11-06 真机根因：页面拦截器已经设过一次 synjones-auth，
        // 我们又 setRequestHeader 一次，浏览器把两次的值合并成
        // "bearer <A>, bearer <B>"（实测头长 1242 = 两份 620 字符），
        // 服务端解析失败 → 401 4030「服务大厅未授权(1)」。
        // 因此：缴费接口的头必须在 setRequestHeader 里**当场扣下**，
        // 只在 send() 里补发唯一的一份。
        val js = buildChargeAppPayTokenJs("t")
        val setHeaderBody = js.substringAfter("XMLHttpRequest.prototype.setRequestHeader = function")
            .substringBefore("XMLHttpRequest.prototype.send = function")
        assertTrue("缴费接口必须扣下，交给 send() 统一发", setHeaderBody.contains("if (isPayUrl(this.__cfUrl) && sameHost(this.__cfUrl)) return;"))

        val sendBody = js.substringAfter("XMLHttpRequest.prototype.send = function")
            .substringBefore("if (window.fetch")
        // send() 里对 synjones-auth 的写入只能出现一处（唯一一次）
        val writes = Regex("rawSetHeader\\.call\\(this, 'synjones-auth'").findAll(sendBody).count()
        assertEquals("send() 里只能设置一次 synjones-auth", 1, writes)
    }

    @Test
    fun scriptKeepsNonPayEndpointsWorking() {
        // 回归护栏（2026-11-06 第一次事故）：非缴费接口必须照常带鉴权头，
        // 否则 /charge/feeitem/singleFeeitem、/charge/user/caslogin_userxx 全 401，
        // 账单列表空白、缴费页只剩「账单明细」。
        val js = buildChargeAppPayTokenJs("t")
        val setHeaderBody = js.substringAfter("XMLHttpRequest.prototype.setRequestHeader = function")
            .substringBefore("XMLHttpRequest.prototype.send = function")
        // 非缴费分支要把页面值规范化后原样发出
        assertTrue(setHeaderBody.contains("return rawSetHeader.call(this, 'synjones-auth', 'bearer ' + own);"))
        // 空值时不发这个头，而不是发一个 "bearer " 空壳
        assertTrue(setHeaderBody.contains("return; // 页面给的是空值/null"))
    }

    @Test
    fun scriptNormalizesToSingleBearerPrefix() {
        // 双重前缀 "bearer bearer <jwt>" 同样会被服务端判 4030
        val js = buildChargeAppPayTokenJs("t")
        assertTrue(js.contains("bearer\\s+"))
        assertTrue(js.contains("'bearer ' +"))
    }

    @Test
    fun scriptDoesNotWritePageOwnSessionStorageKey() {
        // 页面 Logout mutation 里有 sessionStorage.clear()，那是它主动登出；
        // 我们写回 access_token_pay 会让登出永远不生效（401 → 登出 → 复活 → 401 死循环）。
        // 令牌只用自己的键存，现取现用。
        val js = buildChargeAppPayTokenJs("t")
        assertFalse(js.contains("'access_token_pay'"))
        assertFalse(js.contains("\"access_token_pay\""))
        assertTrue(js.contains("'cf_pay_token'"))
    }

    @Test
    fun scriptExposesCaptureHookForSpaRouteChanges() {
        // 页面 replaceState 抹 query 的时机不确定，Kotlin 侧在每次
        // doUpdateVisitedHistory 里调用 __cfPayCapture 再捞一次
        val js = buildChargeAppPayTokenJs("t")
        assertTrue(js.contains("__cfPayCapture"))
    }

    @Test
    fun scriptReadsTokenFromBothQueryNames() {
        // 平台在不同入口分别用 token= 与 synjones-auth= 两种写法
        val js = buildChargeAppPayTokenJs("t")
        assertTrue(js.contains("'token'"))
        assertTrue(js.contains("'synjones-auth'"))
    }

    @Test
    fun scriptNeverUsesRawUnescapedToken() {
        // 令牌里有 . 和 _，正常；但要确保我们是转义后的字面量而不是裸拼字符串，
        // 否则带引号/反斜杠的异常值会把脚本打断。
        val tricky = "a\"b\\c"
        val js = buildChargeAppPayTokenJs(tricky)
        assertTrue(js.contains("a\\\"b\\\\c"))
        assertTrue(js.contains("BAKED"))
    }

    @Test
    fun quoteJsStringLiteralEscapesControlCharacters() {
        assertEquals("\"abc\"", quoteJsStringLiteral("abc"))
        assertEquals("\"\"", quoteJsStringLiteral(""))
        assertEquals("\"a\\\"b\"", quoteJsStringLiteral("a\"b"))
        assertEquals("\"a\\\\b\"", quoteJsStringLiteral("a\\b"))
        assertEquals("\"a\\nb\"", quoteJsStringLiteral("a\nb"))
        assertEquals("\"a\\rb\"", quoteJsStringLiteral("a\rb"))
        assertEquals("\"a\\tb\"", quoteJsStringLiteral("a\tb"))
        // U+2028 / U+2029 在旧 JS 里是换行符，会把字面量截断
        assertEquals("\"a\\u2028b\"", quoteJsStringLiteral("a\u2028b"))
        assertEquals("\"a\\u2029b\"", quoteJsStringLiteral("a\u2029b"))
    }
}
