package com.shiro.classflow.data.network.wbu

/**
 * WBU 专用网络策略的纯逻辑部分（不依赖 Android，便于单测）。
 *
 * 真机实测（2026-11）厘清了两件事，结论与最初的判断相反，这里记下来免得再走回头路：
 *
 * 1. **系统 HTTP 代理确实要绕**：Clash 类 App 把系统代理设成 `127.0.0.1:7890`，
 *    OkHttp 默认跟随，请求被塞进本机回环代理 → 校内侧服务回 502 / 空响应。
 *    `curl` 不读 Android 的 `http_proxy`，所以「命令行能通、App 不能」。
 * 2. **DNS 不能改**：校园域名在公网 DNS 上的记录是死的
 *    （`yktyd` → `183.95.36.17:81` TCP 不通；`jwxt` → `183.95.36.46:80` refused），
 *    能用的只有校内 `172.16.x.x`，而拿它的途径就是 fake-IP 通道本身。
 *    所以「发现 fake-IP 就换物理 DNS」会把唯一可用的通道切断，
 *    对校园 VPN / 分隧道代理尤其致命。
 *
 * 本类因此只保留两项判断：本机回环代理识别（路由用）、fake-IP 识别（**仅用于诊断文案**）。
 */
internal object WbuNetworkPolicy {

    /**
     * 常见 TUN / 代理的 fake-IP 网段。
     *
     * **只用于诊断展示，禁止用来参与路由决策**（见类注释：改 DNS 会切断代理通道）。
     *
     * - `28.0.0.0/8`：Clash for Android（本机实测就是 `28.0.0.x`）；
     * - `198.18.0.0/15`：Clash.Meta / mihomo 默认；
     * - `240.0.0.0/4`、`0.0.0.0`：保留段，不可能作为真实服务地址。
     */
    fun isLikelyFakeIp(raw: String): Boolean {
        val octets = raw.trim().split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 28 -> true
            a == 198 && (b == 18 || b == 19) -> true
            a >= 240 -> true
            a == 0 -> true
            else -> false
        }
    }

    /**
     * 是否是「本机回环代理」。
     *
     * Android 里系统代理常常是**未解析**的 `InetSocketAddress`：
     * `hostString = "127.0.0.1"`，而 `address = null`，所以不能只看
     * `InetAddress.isLoopbackAddress`。这里只接受 IP 字面量与 `localhost`，绝不触发 DNS。
     *
     * 只判回环：远程代理（公司代理、用户手动配置的正经代理）保持原样，
     * 不越权改变用户的网络配置。
     */
    fun isLoopbackProxyHost(host: String?): Boolean {
        val normalized = host?.trim()?.lowercase() ?: return false
        if (normalized.isEmpty()) return false
        if (normalized == "localhost") return true
        if (normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") return true
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return isLoopbackProxyHost(normalized.substring(1, normalized.length - 1))
        }
        val octets = normalized.split('.')
        if (octets.size != 4) return false
        if (octets.any { it.toIntOrNull()?.let { n -> n !in 0..255 } ?: true }) return false
        return octets[0].toInt() == 127
    }
}
