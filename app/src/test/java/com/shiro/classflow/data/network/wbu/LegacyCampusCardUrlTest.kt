package com.shiro.classflow.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 旧一卡通登录 / 账单页 URL 与表单字段。 */
class LegacyCampusCardUrlTest {

    @Test
    fun `CAS service 把旧平台入口完整编码`() {
        val url = LegacyCampusCardUrls.casLoginUrl()

        assertTrue(url.startsWith("http://ids.wbu.edu.cn/authserver/login?service="))
        // continueurl 里原本已编码的 :// 会被再编码一次，与浏览器实际发出的 service 参数一致
        assertTrue(url.contains("sysid%3DFWDT"))
        assertTrue(url.contains("%26continueurl%3Dhttp%253A%252F%252Fyktyd.wbu.edu.cn%253A81%252Fcassyno%252Findex"))
    }

    @Test
    fun `账单页表单字段与旧平台一致`() {
        val form = LegacyCampusCardUrls.pageForm(LegacyCampusCardUrls.FLOW_BILL)

        assertEquals("150", form["flowID"])
        assertEquals("3", form["type"])
        assertEquals("2", form["apptype"])
        assertEquals("1", form["webheadhide"])
        assertEquals("0", form["boothcard"])
        assertEquals("0", form["parm11"])
    }

    @Test
    fun `流水页用 flowID 15`() {
        val form = LegacyCampusCardUrls.pageForm(LegacyCampusCardUrls.FLOW_FLOW)
        assertEquals("15", form["flowID"])
    }

    @Test
    fun `所有旧平台请求固定移动端 UA`() {
        assertTrue(LegacyCampusCardUrls.MOBILE_UA.contains("Mobile"))
        assertFalse(LegacyCampusCardUrls.MOBILE_UA.contains("Windows NT"))
    }

    @Test
    fun `WebVPN 代理子域名按门户规则编码端口`() {
        // 旧平台 http:81（非默认端口）
        assertEquals("yktyd-wbu-edu-cn-81-p", webVpnTargetSlug("yktyd.wbu.edu.cn", 81, false))
        // prelogin http:8090
        assertEquals("yktyd-wbu-edu-cn-8090-p", webVpnTargetSlug("yktyd.wbu.edu.cn", 8090, false))
        // 已知的教务 / 统一认证映射保持不变
        assertEquals("jwxt-wbu-edu-cn-s", webVpnTargetSlug("jwxt.wbu.edu.cn", 443, true))
        assertEquals("ids-wbu-edu-cn", webVpnTargetSlug("ids.wbu.edu.cn", 80, false))
    }

    @Test
    fun `登录跳转与登录页能被识别`() {
        assertTrue(LegacyCampusCardUrls.looksLikeLoginPage("http://yktyd.wbu.edu.cn:81/Phone/Login", "{}"))
        assertTrue(
            LegacyCampusCardUrls.looksLikeLoginPage(
                "http://yktyd.wbu.edu.cn:81/PPage/ComePage?flowID=150",
                "<html><head><title>登录</title></head></html>"
            )
        )
        assertFalse(
            LegacyCampusCardUrls.looksLikeLoginPage(
                "http://yktyd.wbu.edu.cn:81/PPage/ComePage?flowID=150",
                """{"total":1,"rows":[]}"""
            )
        )
    }
}
