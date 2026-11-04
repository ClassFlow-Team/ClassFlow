package com.shiro.classflow.data.network.wbu

import com.xingheyuzhuan.shiguangschedule.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusLinkRouterTest {

    @Test
    fun parsesWaterUri() {
        val dest = CampusLinkRouter.parse("https://ujing.example.com/w/0011202004140940")
        assertNotNull(dest)
        assertTrue(dest is Destination.UjingWater)
        assertEquals("0011202004140940", (dest as Destination.UjingWater).cd)
    }

    @Test
    fun parsesWasherUri() {
        val dest = CampusLinkRouter.parse("https://ujing.example.com/wm/0000000000000A1234567202208040004678")
        assertNotNull(dest)
        assertTrue(dest is Destination.WebApp)
        val webApp = dest as Destination.WebApp
        assertEquals(com.shiro.classflow.data.model.wbu.WebAppId.CAMPUS_CARD.name, webApp.appId)
        assertEquals("http://app.littleswan.com/u_download.html?type=Ujing&uuid=0000000000000A1234567202208040004678", webApp.pendingAutoScan)
    }

    @Test
    fun parsesShowerYktUri() {
        val dest = CampusLinkRouter.parse("https://wbu.pennote.cn/s/y/10101")
        assertNotNull(dest)
        assertTrue(dest is Destination.ShowerDirect)
        val shower = dest as Destination.ShowerDirect
        assertEquals(CampusShowerLink.SYSTEM_YKT, shower.system)
        assertEquals("10101", shower.code)
        assertNull(shower.port)
    }

    @Test
    fun parsesShowerLifeUriWithPort() {
        val shower = CampusLinkRouter.parse("https://wbu.pennote.cn/s/l/1234567890/2") as Destination.ShowerDirect
        assertEquals(CampusShowerLink.SYSTEM_LIFE, shower.system)
        assertEquals("1234567890", shower.code)
        assertEquals("2", shower.port)
    }

    @Test
    fun ignoresShowerLikeUriThatIsNotADevice() {
        assertNull(CampusLinkRouter.parse("https://wbu.pennote.cn/s/y/abc"))
        assertNull(CampusLinkRouter.parse("https://wbu.pennote.cn/s/download"))
    }

    @Test
    fun ignoresUnrelatedUri() {
        val dest = CampusLinkRouter.parse("https://ujing.example.com/other/path")
        assertNull(dest)
    }

    @Test
    fun ignoresDownloadPage() {
        // 官网下载页 /w/download（以及 /wm/download）不能被当成设备码路由
        assertNull(CampusLinkRouter.parse("https://ujing.example.com/w/download"))
        assertNull(CampusLinkRouter.parse("https://ujing.example.com/wm/download"))
        assertNull(CampusLinkRouter.parse("https://ujing.example.com/w/download?from=qr"))
    }

    @Test
    fun parsesClassFlowWaterUri() {
        val dest = CampusLinkRouter.parse("classflow://w/0011202004140940")
        assertNotNull(dest)
        assertTrue(dest is Destination.UjingWater)
        assertEquals("0011202004140940", (dest as Destination.UjingWater).cd)
    }

    @Test
    fun parsesClassFlowTripleSlashUri() {
        val dest = CampusLinkRouter.parse("classflow:///w/0011202004140940")
        assertNotNull(dest)
        assertTrue(dest is Destination.UjingWater)
    }

    @Test
    fun parsesClassFlowShowerUri() {
        val dest = CampusLinkRouter.parse("classflow://s/y/10101")
        assertNotNull(dest)
        assertTrue(dest is Destination.ShowerDirect)
        assertEquals(CampusShowerLink.SYSTEM_YKT, (dest as Destination.ShowerDirect).system)
    }

    @Test
    fun classFlowDownloadPageIsNotADevice() {
        assertNull(CampusLinkRouter.parse("classflow://w/download"))
        assertNull(CampusLinkRouter.parse("classflow://wm/download"))
    }

    @Test
    fun classFlowUnknownPathReturnsNull() {
        assertNull(CampusLinkRouter.parse("classflow://other/abc"))
    }

    @Test
    fun parsesAppPageLinks() {
        assertTrue(CampusLinkRouter.parse("classflow://pay") is Destination.CampusCardPayCode)
        assertTrue(CampusLinkRouter.parse("classflow://pay/") is Destination.CampusCardPayCode)
        assertTrue(CampusLinkRouter.parse("classflow://PAY") is Destination.CampusCardPayCode)
        assertTrue(CampusLinkRouter.parse("classflow://scan") is Destination.QrScan)
    }

    @Test
    fun appPageLinksIgnoreQueryButNotExtraSegments() {
        // query 不参与路径判定：带个来源标记仍然进付款码页
        assertTrue(CampusLinkRouter.parse("classflow://pay?from=nfc") is Destination.CampusCardPayCode)
        // 这两条命名空间是「页」，不接受任何子路径
        assertNull(CampusLinkRouter.parse("classflow://pay/code"))
        assertNull(CampusLinkRouter.parse("classflow://scan/result"))
    }
}
