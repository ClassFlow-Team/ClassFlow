package com.shiro.classflow.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WBU 专用网络策略：本机回环代理识别（路由用）+ fake-IP 识别（仅诊断文案）。
 *
 * 真机依据（2026-11）：
 * - Clash 把系统代理设为 `127.0.0.1:7890`（未解析的 InetSocketAddress），
 *   OkHttp 默认跟随，于是请求被塞进代理 → 旧平台回 502；
 * - 公网 DNS 上的校园记录是死的（`183.95.36.17:81` TCP 不通、`183.95.36.46:80` refused），
 *   所以**不能**再「发现 fake-IP 就换物理 DNS」。
 */
class WbuNetworkPolicyTest {

    @Test
    fun recognisesClashFakeIpRanges() {
        assertTrue(WbuNetworkPolicy.isLikelyFakeIp("28.0.0.81"))
        assertTrue(WbuNetworkPolicy.isLikelyFakeIp("28.0.0.153"))
        assertTrue(WbuNetworkPolicy.isLikelyFakeIp("28.255.1.1"))
        assertTrue(WbuNetworkPolicy.isLikelyFakeIp("198.18.0.5"))
        assertTrue(WbuNetworkPolicy.isLikelyFakeIp("198.19.255.254"))
        assertTrue(WbuNetworkPolicy.isLikelyFakeIp("240.0.0.1"))
        assertTrue(WbuNetworkPolicy.isLikelyFakeIp("0.0.0.0"))
    }

    @Test
    fun keepsRealCampusAndPublicAddresses() {
        assertFalse(WbuNetworkPolicy.isLikelyFakeIp("172.16.87.5"))
        assertFalse(WbuNetworkPolicy.isLikelyFakeIp("172.18.0.123"))
        assertFalse(WbuNetworkPolicy.isLikelyFakeIp("183.95.36.3"))
        assertFalse(WbuNetworkPolicy.isLikelyFakeIp("120.192.84.244"))
        assertFalse(WbuNetworkPolicy.isLikelyFakeIp("10.0.0.1"))
        assertFalse(WbuNetworkPolicy.isLikelyFakeIp("198.17.0.1"))
    }

    @Test
    fun rejectsMalformedAddresses() {
        assertFalse(WbuNetworkPolicy.isLikelyFakeIp(""))
        assertFalse(WbuNetworkPolicy.isLikelyFakeIp("abc"))
        assertFalse(WbuNetworkPolicy.isLikelyFakeIp("1.2.3"))
        assertFalse(WbuNetworkPolicy.isLikelyFakeIp("1.2.3.4.5"))
        assertFalse(WbuNetworkPolicy.isLikelyFakeIp("256.0.0.1"))
    }

    @Test
    fun detectsLoopbackProxies() {
        // 真机实测：系统代理就是这个形态（未解析的 InetSocketAddress）
        assertTrue(WbuNetworkPolicy.isLoopbackProxyHost("127.0.0.1"))
        assertTrue(WbuNetworkPolicy.isLoopbackProxyHost("127.1.2.3"))
        assertTrue(WbuNetworkPolicy.isLoopbackProxyHost("localhost"))
        assertTrue(WbuNetworkPolicy.isLoopbackProxyHost("::1"))
        assertTrue(WbuNetworkPolicy.isLoopbackProxyHost("[::1]"))
    }

    @Test
    fun keepsRemoteProxiesUntouched() {
        // 公司代理 / 用户手动配置的正经代理：不能擅自绕过
        assertFalse(WbuNetworkPolicy.isLoopbackProxyHost("10.0.0.8"))
        assertFalse(WbuNetworkPolicy.isLoopbackProxyHost("192.168.1.1"))
        assertFalse(WbuNetworkPolicy.isLoopbackProxyHost("proxy.example.com"))
        assertFalse(WbuNetworkPolicy.isLoopbackProxyHost("183.95.36.3"))
        assertFalse(WbuNetworkPolicy.isLoopbackProxyHost(""))
        assertFalse(WbuNetworkPolicy.isLoopbackProxyHost(null))
        assertFalse(WbuNetworkPolicy.isLoopbackProxyHost("128.0.0.1"))
        assertFalse(WbuNetworkPolicy.isLoopbackProxyHost("127.0.0"))
    }

    // ---------------- 诊断文案 ----------------

    @Test
    fun describesFakeIpAsLocalProxyNotPublicInternet() {
        // 关键回归：Clash 的 28.0.0.x 以前会被说成「公网（可能经过云 WAF）」，把人带偏
        assertEquals(
            "本地代理接管（fake-IP）",
            WbuNetworkDiagnostics.describePath("28.0.0.153")
        )
        assertEquals(
            "本地代理接管（fake-IP）",
            WbuNetworkDiagnostics.describePath("198.18.0.4")
        )
    }

    @Test
    fun describesCampusAddressAsDirect() {
        assertEquals(
            "校内直连（未过云 WAF）",
            WbuNetworkDiagnostics.describePath("172.16.87.5")
        )
        assertEquals(
            "校内直连（未过云 WAF）",
            WbuNetworkDiagnostics.describePath("172.18.0.123")
        )
    }

    @Test
    fun describesPublicAddressAsPossiblyWaf() {
        assertEquals(
            "公网（可能经过云 WAF）",
            WbuNetworkDiagnostics.describePath("183.95.36.3")
        )
    }

    @Test
    fun describesMissingPeerAsUnknown() {
        assertEquals("未知（没连上）", WbuNetworkDiagnostics.describePath(null))
        assertEquals("未知（没连上）", WbuNetworkDiagnostics.describePath(""))
    }
}
