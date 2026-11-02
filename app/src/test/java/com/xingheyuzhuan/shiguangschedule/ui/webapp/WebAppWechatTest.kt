package com.xingheyuzhuan.shiguangschedule.ui.webapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微信相关的 URL 识别与解析（[WebAppWechat]）。
 *
 * 这里的样例全部来自实测（见移动门户研究报告 §11.5 / §11.7）：
 * - 必须微信类：`open.weixin.qq.com/connect/oauth2/authorize`（一卡通微信服务、超星系）、
 *   企业微信授权页 `open.work.weixin.qq.com`；
 * - 可以跳过类：统一身份认证的企业微信中转
 *   `ids.wbu.edu.cn/authserver/weixinQYLogin.do?qyId=…&success=…`，
 *   它的 `success` 就是一个 CAS 登录地址，客户端换票即可跳过微信。
 */
class WebAppWechatTest {

    /** 门户里「一卡通（微信服务）」实测的授权地址（已省略 redirect_uri 中的细节）。 */
    private val wechatAuthorizeUrl =
        "https://open.weixin.qq.com/connect/oauth2/authorize" +
            "?appid=wx5a34af927450044e" +
            "&redirect_uri=http%3A%2F%2Fyktyd.wbu.edu.cn%2Fwechat%2Furl%2Fredirect.html%3Fjkbh%3D0005" +
            "&response_type=code&scope=snsapi_base&state=wechat_portal&connect_redirect=1#wechat_redirect"

    /** 门户「新生入馆教育」实测的企业微信中转地址（success 未编码、且是最后一个参数）。 */
    private val weixinQyLoginUrl =
        "http://ids.wbu.edu.cn/authserver/weixinQYLogin.do?qyId=wx5a34af927450044e" +
            "&success=http://ids.wbu.edu.cn/authserver/login?service=http://zxst.wbu.edu.cn:6060/Web/CASDefault"

    @Test
    fun recognisesWechatAuthorizePages() {
        assertTrue(WebAppWechat.isWechatAuthorizeUrl(wechatAuthorizeUrl))
        // qrconnect 同族
        assertTrue(WebAppWechat.isWechatAuthorizeUrl("https://open.weixin.qq.com/connect/qrconnect?appid=x"))
        // 企业微信工作台授权
        assertTrue(WebAppWechat.isWechatAuthorizeUrl("https://open.work.weixin.qq.com/wwopen/sso/qrConnect?appid=x"))
    }

    @Test
    fun ignoresNonAuthorizeWechatPages() {
        // 同一宿主下的下载页 / 帮助页不是授权入口，不该被拦
        assertFalse(WebAppWechat.isWechatAuthorizeUrl("https://open.weixin.qq.com/cgi-bin/download?url=x"))
        assertFalse(WebAppWechat.isWechatAuthorizeUrl("https://ydmh.wbu.edu.cn/ydmh/html/yd/index.html"))
        assertFalse(WebAppWechat.isWechatAuthorizeUrl("about:blank"))
    }

    @Test
    fun recognisesWeixinQyLoginOnIdsHostOnly() {
        assertTrue(WebAppWechat.isWeixinQyLoginUrl(weixinQyLoginUrl))
        // 别处的同名路径不算（避免误跳过）
        assertFalse(
            WebAppWechat.isWeixinQyLoginUrl(
                "http://example.com/authserver/weixinQYLogin.do?success=http://ids.wbu.edu.cn/x"
            )
        )
    }

    @Test
    fun parsesRawSuccessTargetIncludingItsOwnQuery() {
        assertEquals(
            "http://ids.wbu.edu.cn/authserver/login?service=http://zxst.wbu.edu.cn:6060/Web/CASDefault",
            WebAppWechat.parseSuccessTarget(weixinQyLoginUrl)
        )
    }

    @Test
    fun parsesEncodedSuccessTarget() {
        val encoded = "http://ids.wbu.edu.cn/authserver/weixinQYLogin.do?qyId=wx1" +
            "&success=http%3A%2F%2Fids.wbu.edu.cn%2Fauthserver%2Flogin%3Fservice%3D" +
            "http%253A%252F%252Fydpj.wbu.edu.cn%253A8008%252FjzLogin"
        assertEquals(
            "http://ids.wbu.edu.cn/authserver/login?service=http%3A%2F%2Fydpj.wbu.edu.cn%3A8008%2FjzLogin",
            WebAppWechat.parseSuccessTarget(encoded)
        )
    }

    @Test
    fun returnsNullWhenThereIsNoSuccessParam() {
        assertNull(WebAppWechat.parseSuccessTarget("http://ids.wbu.edu.cn/authserver/weixinQYLogin.do?qyId=wx1"))
    }

    @Test
    fun trimsStrayWhitespaceAroundParsedTargets() {
        // Location / 页面地址里偶尔带一个尾随空格：原样交给 WebView 会被编码成 `%20`，
        // 变成另一条路径（实测 `…/ydmh%20` 会被 Tomcat 直接 404），所以解析时要抹掉
        assertEquals(
            "http://ids.wbu.edu.cn/authserver/login?service=http://zxst.wbu.edu.cn:6060/Web/CASDefault",
            WebAppWechat.parseSuccessTarget("$weixinQyLoginUrl ")
        )
        assertEquals(
            "http://zxst.wbu.edu.cn:6060/Web/CASDefault",
            WebAppWechat.casServiceOf("http://ids.wbu.edu.cn/authserver/login?service=http://zxst.wbu.edu.cn:6060/Web/CASDefault ")
        )
    }

    @Test
    fun extractsCasServiceFromRawLoginUrl() {
        assertEquals(
            "http://zxst.wbu.edu.cn:6060/Web/CASDefault",
            WebAppWechat.casServiceOf(
                "http://ids.wbu.edu.cn/authserver/login?service=http://zxst.wbu.edu.cn:6060/Web/CASDefault"
            )
        )
    }

    @Test
    fun extractsCasServiceFromEncodedLoginUrl() {
        assertEquals(
            "http://ehall.wbu.edu.cn/xsfwdt/sys/swmxsqjapp/*default/index.do",
            WebAppWechat.casServiceOf(
                "http://ids.wbu.edu.cn/authserver/login" +
                    "?service=http%3A%2F%2Fehall.wbu.edu.cn%2Fxsfwdt%2Fsys%2Fswmxsqjapp%2F%2Adefault%2Findex.do"
            )
        )
    }

    @Test
    fun detectsCasLoginPages() {
        assertTrue(WebAppWechat.isCasLoginUrl("http://ids.wbu.edu.cn/authserver/login?service=x"))
        assertTrue(WebAppWechat.isCasLoginUrl("https://webvpn.wbu.edu.cn/por/login"))
        assertFalse(WebAppWechat.isCasLoginUrl("http://ehall.wbu.edu.cn/index.do?ticket=ST-1"))
    }

    @Test
    fun prefersThePageUrlWhenCopying() {
        // 门户里的应用入口（在微信里能直接打开）优先于微信自己的授权页
        val page = "http://yktyd.wbu.edu.cn/wechat/url/redirectJkbh.html?jkbh=0005"
        assertEquals(page, WebAppWechat.pickCopyUrl(page, wechatAuthorizeUrl))
    }

    @Test
    fun fallsBackToBlockedUrlWhenPageIsNotUseful() {
        assertEquals(wechatAuthorizeUrl, WebAppWechat.pickCopyUrl(wechatAuthorizeUrl, wechatAuthorizeUrl))
        assertEquals(wechatAuthorizeUrl, WebAppWechat.pickCopyUrl(null, wechatAuthorizeUrl))
        assertEquals(wechatAuthorizeUrl, WebAppWechat.pickCopyUrl("about:blank", wechatAuthorizeUrl))
    }

    @Test
    fun hostOfParsesTargetSite() {
        assertEquals(
            "yktyd.wbu.edu.cn",
            WebAppWechat.hostOf("http://yktyd.wbu.edu.cn/wechat/url/redirectJkbh.html?jkbh=0005")
        )
        assertEquals("open.weixin.qq.com", WebAppWechat.hostOf(wechatAuthorizeUrl))
        assertNull(WebAppWechat.hostOf("about:blank"))
    }

    @Test
    fun cheapGateCheckMatchesOnlyWechatCandidates() {
        assertTrue(WebAppWechat.looksLikeWechatGate(wechatAuthorizeUrl))
        assertTrue(WebAppWechat.looksLikeWechatGate(weixinQyLoginUrl))
        assertFalse(WebAppWechat.looksLikeWechatGate("http://ehall.wbu.edu.cn/index.do"))
    }
}
