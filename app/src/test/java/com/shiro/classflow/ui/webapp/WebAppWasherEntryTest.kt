package com.shiro.classflow.ui.webapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这次进的是不是洗衣机 H5」的判定（[isWasherRaw]）。
 *
 * 这条判据决定**退出网页容器后往哪落**：是的人要落到洗衣机页（进度 + 账单 + 洗完提醒），
 * 不是的人照旧返回上一页。判错了两边都难受 ——
 * 漏判：用户扫完码退出，被送回课表，看不到刚下的单，也没地方设提醒；
 * 误判：逛一卡通门户、看饮水机页面的人，退出时被莫名带进洗衣机页。
 *
 * 所以这里把洗衣机贴纸的几种真实写法都钉住，尤其是不含 `littleswan.com` 的那种。
 */
class WebAppWasherEntryTest {

    @Test
    fun littleswanStickerCountsAsWasher() {
        assertTrue(
            isWasherRaw("http://app.littleswan.com/u_download.html?type=Ujing&uuid=0a1b2c3d4e")
        )
    }

    @Test
    fun ujingUuidLinkCountsAsWasher() {
        // 部分贴纸给的是 U净 域名 + uuid，不含 littleswan.com：
        // 旧判据（字符串匹配 littleswan.com）会漏掉，退出后回不到洗衣机页
        assertTrue(isWasherRaw("https://q.ujing.com.cn/device?uuid=0a1b2c3d4e"))
    }

    @Test
    fun alipayWrappedWasherLinkCountsAsWasher() {
        // 支付宝包装层的扫码原文：真正的小天鹅地址在 scheme → codeContent 里（多层转义）
        val innerRaw = "http://app.littleswan.com/u_download.html?type=Ujing&uuid=0a1b2c3d4e"
        val scheme = "alipays://platformapi/startapp?appId=10000007&actionType=route&codeContent=" +
            java.net.URLEncoder.encode(innerRaw, "UTF-8")
        val wrapped = "https://render.alipay.com/p/s/ulink/sn?s=dc&scheme=" +
            java.net.URLEncoder.encode(scheme, "UTF-8")
        assertTrue(isWasherRaw(wrapped))
    }

    @Test
    fun waterAndHairdryerDoNotCountAsWasher() {
        assertFalse(isWasherRaw("http://q.ujing.com.cn/ed/index.html?cd=0014202206120446"))
        assertFalse(isWasherRaw("http://q.ujing.com.cn/6d/index.html?cd=0014202206120446"))
        // 洗浴
        assertFalse(isWasherRaw("https://wbu.pennote.cn/s/y/12345"))
    }

    @Test
    fun blankDoesNotCountAsWasher() {
        assertFalse(isWasherRaw(null))
        assertFalse(isWasherRaw(""))
        assertFalse(isWasherRaw("  "))
        // 普通网页入口（一卡通 / 图书馆）也没有扫码原文
        assertFalse(isWasherRaw("https://yktfwpt.wbu.edu.cn/plat/shouyeUser"))
    }
}
