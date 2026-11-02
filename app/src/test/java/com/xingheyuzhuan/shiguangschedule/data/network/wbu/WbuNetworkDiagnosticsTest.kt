package com.xingheyuzhuan.shiguangschedule.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络诊断里「这是校内直连还是过云 WAF」的单测。
 *
 * 判据只按私网段。校内 DNS 把 `ids.wbu.edu.cn` 解析到 `172.18.0.123`（实测），
 * 公网 / 蜂窝解析到 WAF 节点（`220.203.56.120`、`116.211.138.211` 等）。判错方向会让
 * 「打水失败」的排查完全跑偏，所以把边界钉住。
 */
class WbuNetworkDiagnosticsTest {

    @Test
    fun campusAddressRangeIsRecognised() {
        assertTrue(WbuNetworkDiagnostics.isPrivateAddress("172.18.0.123"))
        assertTrue(WbuNetworkDiagnostics.isPrivateAddress("172.16.0.1"))
        assertTrue(WbuNetworkDiagnostics.isPrivateAddress("172.31.255.254"))
        assertTrue(WbuNetworkDiagnostics.isPrivateAddress("10.163.195.158"))
        assertTrue(WbuNetworkDiagnostics.isPrivateAddress("192.168.123.169"))
    }

    @Test
    fun publicWafNodesAreNotTreatedAsCampus() {
        // 实测的 WAF 节点
        assertFalse(WbuNetworkDiagnostics.isPrivateAddress("220.203.56.120"))
        assertFalse(WbuNetworkDiagnostics.isPrivateAddress("101.206.185.116"))
        assertFalse(WbuNetworkDiagnostics.isPrivateAddress("116.211.138.211"))
        assertFalse(WbuNetworkDiagnostics.isPrivateAddress("66.90.98.146"))
        // 172.15 / 172.32 不在 172.16/12 里，不能算内网
        assertFalse(WbuNetworkDiagnostics.isPrivateAddress("172.15.0.1"))
        assertFalse(WbuNetworkDiagnostics.isPrivateAddress("172.32.0.1"))
    }

    @Test
    fun pathDescriptionSeparatesTheTwoRoutes() {
        assertEquals("校内直连（未过云 WAF）", WbuNetworkDiagnostics.describePath("172.18.0.123"))
        assertEquals("公网（可能经过云 WAF）", WbuNetworkDiagnostics.describePath("220.203.56.120"))
        // 没连上时不能编一个路径出来
        assertEquals("未知（没连上）", WbuNetworkDiagnostics.describePath(null))
        assertEquals("未知（没连上）", WbuNetworkDiagnostics.describePath(""))
    }
}
