package com.shiro.classflow.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学校 DNS 解析校园域名的判据与报文解析。
 *
 * 背景（真机实测 2026-11，校园网 + Clash）：
 * - 裸 IP `172.16.87.5:81` → 200；域名 `yktyd.wbu.edu.cn` → fake-IP `28.0.0.158` → TCP 超时；
 * - 手工查学校 DNS `218.199.208.8` → 真实 `172.16.87.5`（不被劫持）；
 * - 但 `dns.wbu.edu.cn` 经系统解析得到的是 fake-IP `28.0.0.5`，拿它当 DNS 服务器会返回
 *   **失效的公网映射** `183.95.36.17` —— 所以兜底被否掉，只写死一个 IP。
 */
class CampusDnsPolicyTest {

    // ---------------- 域名范围 ----------------

    @Test
    fun `只接管 wbu 校园域名`() {
        assertTrue(CampusDnsPolicy.isCampusHost("yktyd.wbu.edu.cn"))
        assertTrue(CampusDnsPolicy.isCampusHost("webvpn.wbu.edu.cn"))
        assertTrue(CampusDnsPolicy.isCampusHost("ids.wbu.edu.cn"))
        assertTrue(CampusDnsPolicy.isCampusHost("wbu.edu.cn"))
        assertTrue(CampusDnsPolicy.isCampusHost("YKTYD.WBU.EDU.CN"))
        assertTrue(CampusDnsPolicy.isCampusHost("  jwxt.wbu.edu.cn  "))
    }

    @Test
    fun `不接管非校园域名`() {
        // 关键回归：后缀伪装绝不能被当成校园域名
        assertFalse(CampusDnsPolicy.isCampusHost("wbu.edu.cn.evil.com"))
        assertFalse(CampusDnsPolicy.isCampusHost("notwbu.edu.cn"))
        assertFalse(CampusDnsPolicy.isCampusHost("example.com"))
        assertFalse(CampusDnsPolicy.isCampusHost(""))
        assertFalse(CampusDnsPolicy.isCampusHost("  "))
    }

    // ---------------- 答案可用性 ----------------

    @Test
    fun `只接受私网地址`() {
        assertTrue(CampusDnsPolicy.isUsableCampusAnswer("172.16.87.5"))
        assertTrue(CampusDnsPolicy.isUsableCampusAnswer("172.18.0.123"))
        assertTrue(CampusDnsPolicy.isUsableCampusAnswer("172.31.255.254"))
        assertTrue(CampusDnsPolicy.isUsableCampusAnswer("10.0.0.1"))
        assertTrue(CampusDnsPolicy.isUsableCampusAnswer("192.168.1.1"))
    }

    @Test
    fun `丢弃公网映射与 fake-IP`() {
        // 公网映射实测是死的（:81 TCP 不通），用它们只会把失败换得更慢
        assertFalse(CampusDnsPolicy.isUsableCampusAnswer("183.95.36.17"))
        assertFalse(CampusDnsPolicy.isUsableCampusAnswer("183.95.36.3"))
        assertFalse(CampusDnsPolicy.isUsableCampusAnswer("8.8.8.8"))
        // fake-IP 段也不是私网，天然被排除
        assertFalse(CampusDnsPolicy.isUsableCampusAnswer("28.0.0.158"))
        assertFalse(CampusDnsPolicy.isUsableCampusAnswer("198.18.0.5"))
        assertFalse(CampusDnsPolicy.isUsableCampusAnswer("172.15.0.1"))
        assertFalse(CampusDnsPolicy.isUsableCampusAnswer("172.32.0.1"))
        assertFalse(CampusDnsPolicy.isUsableCampusAnswer("abc"))
    }

    // ---------------- 三条红线 ----------------

    @Test
    fun `直连且可达且开关开才启用`() {
        assertTrue(
            CampusDnsPolicy.shouldUseSchoolDns(
                host = "yktyd.wbu.edu.cn",
                useVpn = false,
                campusReachable = true,
                enabled = true
            )
        )
    }

    @Test
    fun `WebVPN 通道永不启用`() {
        // 红线：webvpn.wbu.edu.cn 必须解析成 fake-IP 才能进隧道。
        // 换成真实 IP 会让请求绕过 tunnels 规则 → WebVPN 直接不可用。
        assertFalse(
            CampusDnsPolicy.shouldUseSchoolDns(
                host = "webvpn.wbu.edu.cn",
                useVpn = true,
                campusReachable = true,
                enabled = true
            )
        )
        // 任何校园域名都一样
        assertFalse(
            CampusDnsPolicy.shouldUseSchoolDns(
                host = "yktyd.wbu.edu.cn",
                useVpn = true,
                campusReachable = true,
                enabled = true
            )
        )
    }

    @Test
    fun `开关关闭后完全等于不装`() {
        // 红线：关掉即零副作用，行为必须等于修复前
        assertFalse(
            CampusDnsPolicy.shouldUseSchoolDns(
                host = "yktyd.wbu.edu.cn",
                useVpn = false,
                campusReachable = true,
                enabled = false
            )
        )
    }

    @Test
    fun `探测不可达时不启用`() {
        assertFalse(
            CampusDnsPolicy.shouldUseSchoolDns(
                host = "yktyd.wbu.edu.cn",
                useVpn = false,
                campusReachable = false,
                enabled = true
            )
        )
    }

    @Test
    fun `非校园域名不启用`() {
        assertFalse(
            CampusDnsPolicy.shouldUseSchoolDns(
                host = "www.baidu.com",
                useVpn = false,
                campusReachable = true,
                enabled = true
            )
        )
    }

    // ---------------- DNS 报文 ----------------

    @Test
    fun `查询报文能被解析回原域名`() {
        val query = SchoolDns.buildQuery("yktyd.wbu.edu.cn")
        requireNotNull(query)
        // header 12 字节 + qname + qtype/qclass 4 字节
        assertEquals(12 + 1 + 5 + 1 + 3 + 1 + 3 + 1 + 2 + 1 + 4, query.size)
        assertEquals(0x12, query[0].toInt() and 0xFF)
        assertEquals(0x34, query[1].toInt() and 0xFF)
        // qdcount = 1
        assertEquals(1, query[5].toInt())
        // 第一段长度是 "yktyd" = 5
        assertEquals(5, query[12].toInt())
    }

    @Test
    fun `非法域名构造不出查询报文`() {
        assertEquals(null, SchoolDns.buildQuery(""))
        assertEquals(null, SchoolDns.buildQuery(".."))
        assertEquals(null, SchoolDns.buildQuery("a".repeat(64) + ".com"))
    }

    @Test
    fun `解析真实应答取出校内地址`() {
        // 真机抓到的应答：yktyd.wbu.edu.cn → 172.16.87.5
        val query = requireNotNull(SchoolDns.buildQuery("yktyd.wbu.edu.cn"))
        val reply = hexToBytes(
            "123485800001000100000001" +
                "05796b747964037762750365647502636e0000010001" +
                "05796b747964037762750365647502636e0000010001" +
                "00000e100004" + "ac105705" +
                "00002900000000000000" + "0c00080008000120000aa0e505"
        )
        assertEquals(listOf("172.16.87.5"), SchoolDns.parseAnswer(reply, query.size))
    }

    @Test
    fun `公网应答被丢弃`() {
        // 阿里 DNS 的答案：yktyd.wbu.edu.cn → 183.95.36.17（实测是死地址）
        val query = requireNotNull(SchoolDns.buildQuery("yktyd.wbu.edu.cn"))
        val reply = hexToBytes(
            "123481800001000100000000" +
                "05796b747964037762750365647502636e0000010001" +
                "c00c0001000100000a8c0004" + "b75f2411"
        )
        assertEquals(emptyList<String>(), SchoolDns.parseAnswer(reply, query.size))
    }

    @Test
    fun `无应答记录时返回空`() {
        val query = requireNotNull(SchoolDns.buildQuery("yktyd.wbu.edu.cn"))
        // ancount = 0
        val reply = hexToBytes("123485800001000000000001" + "05796b747964037762750365647502636e0000010001")
        assertEquals(emptyList<String>(), SchoolDns.parseAnswer(reply, query.size))
    }

    @Test
    fun `NXDOMAIN 之类直接返回空`() {
        val query = requireNotNull(SchoolDns.buildQuery("yktyd.wbu.edu.cn"))
        // rcode = 3 (NXDOMAIN)
        val reply = hexToBytes("123485830001000000000001" + "05796b747964037762750365647502636e0000010001")
        assertEquals(emptyList<String>(), SchoolDns.parseAnswer(reply, query.size))
    }

    @Test
    fun `截断的报文不会崩`() {
        val query = requireNotNull(SchoolDns.buildQuery("yktyd.wbu.edu.cn"))
        assertEquals(emptyList<String>(), SchoolDns.parseAnswer(ByteArray(0), query.size))
        assertEquals(emptyList<String>(), SchoolDns.parseAnswer(ByteArray(5), query.size))
        assertEquals(emptyList<String>(), SchoolDns.parseAnswer(ByteArray(20), query.size))
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
