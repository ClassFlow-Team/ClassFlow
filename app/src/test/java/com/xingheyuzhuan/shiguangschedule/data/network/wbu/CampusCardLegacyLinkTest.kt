package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧一卡通微信服务链接 → 新平台落点的改道规则（[CampusCardLegacyLink]）。
 *
 * 门户里的 7 个一卡通入口全部落在 `yktyd.wbu.edu.cn` 的 `wechat` 路径下（点开必然 302 到企业微信 OAuth），
 * 这里逐条钉住它们改道后的落点，避免以后改映射时改错行
 * （尤其是电费/水费那两条疑似互换的映射，见类注释）。
 */
class CampusCardLegacyLinkTest {

    private fun platform(redirect: CampusCardRedirect?): CampusCardRedirect.PlatformApp {
        assertTrue("应当是平台应用改道，实际 $redirect", redirect is CampusCardRedirect.PlatformApp)
        return redirect as CampusCardRedirect.PlatformApp
    }

    @Test
    fun payCodeGoesToNativePayCodePage() {
        assertEquals(
            CampusCardRedirect.NativePayCode,
            CampusCardLegacyLink.parse("http://yktyd.wbu.edu.cn/wechat/pay/code.html?json=%7B%7D")
        )
    }

    @Test
    fun rechargeGoesToCardRechargeApp() {
        val target = platform(CampusCardLegacyLink.parse("http://yktyd.wbu.edu.cn/wechat/ChZhController/ChongZhiurl.html?json=x"))
        assertEquals("card-recharge", target.appCode)
        assertTrue(target.deepLinkFallback!!.contains("feeitemid=410"))
    }

    @Test
    fun changePasswordGoesToCardSetPwd() {
        val target = platform(
            CampusCardLegacyLink.parse(
                "http://yktyd.wbu.edu.cn/wechat/service/modify.html?json=x&xxbh=synjones&jkbh=0005"
            )
        )
        assertEquals("change-password", target.appCode)
        assertTrue(target.deepLinkFallback!!.contains("cardSetPwd"))
    }

    @Test
    fun electricityGoesToFeeItem412() {
        val target = platform(CampusCardLegacyLink.parse("http://yktyd.wbu.edu.cn/wechat/waterpay/water.html?json=x"))
        assertEquals("electricity", target.appCode)
        assertTrue(target.deepLinkFallback!!.contains("feeitemid=412"))
    }

    @Test
    fun waterGoesToFeeItem413() {
        val target = platform(CampusCardLegacyLink.parse("http://yktyd.wbu.edu.cn/wechat/elecpay/queryelec.html?json=x"))
        assertEquals("water", target.appCode)
        assertTrue(target.deepLinkFallback!!.contains("feeitemid=413"))
    }

    @Test
    fun internetGoesToFeeItem468() {
        val target = platform(CampusCardLegacyLink.parse("http://yktyd.wbu.edu.cn/wechat/networkpay/network.html?json=x"))
        assertEquals("internet", target.appCode)
        assertTrue(target.deepLinkFallback!!.contains("feeitemid=468"))
    }

    @Test
    fun unknownLegacyLinkFallsBackToPlatformHome() {
        val target = platform(CampusCardLegacyLink.parse("http://yktyd.wbu.edu.cn/wechat/url/redirectJkbh.html?jkbh=0005"))
        assertNull(target.appCode)
        assertNull(target.deepLinkFallback)
    }

    @Test
    fun alsoMatchesTheWebVpnProxiedHost() {
        assertEquals(
            CampusCardRedirect.NativePayCode,
            CampusCardLegacyLink.parse("http://yktyd-wbu-edu-cn.webvpn.wbu.edu.cn/wechat/pay/code.html")
        )
    }

    @Test
    fun leavesEverythingElseAlone() {
        // 门户自己、新平台、教务、ehall 都不该被改道
        assertNull(CampusCardLegacyLink.parse("https://ydmh.wbu.edu.cn/ydmh/html/yd/index.html#/pages/home/index"))
        assertNull(CampusCardLegacyLink.parse("http://yktfwpt.wbu.edu.cn/plat/shouyeUser"))
        assertNull(CampusCardLegacyLink.parse("http://ehall.wbu.edu.cn/xsfwdt/sys/swmxsqjapp/*default/index.do"))
        assertNull(CampusCardLegacyLink.parse("not a url"))
    }
}
