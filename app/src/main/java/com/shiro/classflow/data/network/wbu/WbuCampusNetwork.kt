package com.shiro.classflow.data.network.wbu

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.OkHttpClient

/**
 * WBU 校园请求的「本地代理绕行」策略。
 *
 * 真机实测（2026-11）确认：Clash 类代理 App 会把系统代理设成 `127.0.0.1:7890`，
 * 而 OkHttp **默认跟随系统代理**，于是请求被塞进本机回环代理，再由代理按它自己的规则
 * 送去公网映射 —— 校内侧服务（只在校内 `172.16/12`）必然回 502 / 空响应。
 *
 * 注意 `curl` 不受影响：它不读 Android 的 `http_proxy`。这正是「命令行能通、App 不能」的原因，
 * 排查时不要只信 curl。
 *
 * **刻意不做的事：改 DNS。**
 *
 * 曾经试过「系统 DNS 给出 fake-IP 就改用物理网络 DNS」，已撤回，原因是实测数据不支持：
 *
 * - 校园域名在公网 DNS 上的记录**是死的**（`yktyd.wbu.edu.cn` → `183.95.36.17:81` 连 TCP 都不通，
 *   `jwxt.wbu.edu.cn` → `183.95.36.46:80` 直接 refused），只有 `webvpn.wbu.edu.cn` 活着；
 * - 能用的地址只有校内 `172.16.x.x`，而拿它的途径就是 fake-IP 通道本身。
 *
 * 也就是说：fake-IP 不是故障，它是代理把域名交给正确出站规则的**唯一手段**。App 没有立场
 * 判断「这个解析结果不可信」并代用户改掉它 —— 一旦校园 VPN / 分隧道代理也用 fake-IP，
 * 改写会**切断唯一可用的通道**，和「检测到 VPN 就绑物理网卡」是同一类错误。
 *
 * 所以这里只做一件确定性收益的事：**系统代理是本机回环代理时，WBU 请求改直连**。
 * 远程代理（公司代理、用户手动配置的正经代理）保持原样，不越权改变用户的网络配置。
 *
 * ## 2026-11 修正：上面「fake-IP 是唯一手段」这个理由被实测证伪
 *
 * 补充实测（校园网 + Clash）：
 *
 * | 目标 | 结果 |
 * |---|---|
 * | `172.16.87.5:81`（裸 IP） | 200 |
 * | `yktyd.wbu.edu.cn:81`（域名 → `28.0.0.158`） | **000 超时** |
 * | 手工查学校 DNS `218.199.208.8` | 返回真实 `172.16.87.5` |
 *
 * fake-IP 给的那个地址**根本连不上**，它不是「出站规则的入口」而是死路；
 * 真正能用的是裸 IP，而拿它的办法就是用学校 DNS 解析。
 *
 * 于是现在多了一件事（**仅在直连通道、且探测确认能到校内时**）：
 * 用学校 DNS 解析 `*.wbu.edu.cn`，见 [CampusDns]。前置条件、开关、以及为什么必须
 * 排除 WebVPN 通道，都写在 `CampusDnsResolver.kt` 的类注释里。
 */
internal object WbuCampusNetwork {

    private const val TAG = "WbuCampusNetwork"

    /**
     * 把校园网络适配挂到一个 OkHttpClient.Builder 上。
     *
     * @param useVpn 本次通道。**`true` 时绝不装学校 DNS** ——
     *   WebVPN 模式下 `webvpn.wbu.edu.cn` 必须解析成 fake-IP 才能进隧道，
     *   换成真实公网 IP 会让请求绕过隧道规则，直接不可用。
     * @param campusDnsEnabled 开关的实时求值器（lambda，不是 Boolean：
     *   建 client 时是单例，快照会导致用户关掉开关后必须杀进程才生效）。
     * @param campusReachable 校内可达性的实时求值器（内部有 60s 缓存）。
     */
    fun install(
        builder: OkHttpClient.Builder,
        context: android.content.Context? = null,
        useVpn: Boolean = false
    ): OkHttpClient.Builder {
        builder.proxySelector(LoopbackProxyBypass(ProxySelector.getDefault()))
        // 直连通道才可能用到学校 DNS；WebVPN 通道碰它就是自断隧道
        if (!useVpn && context != null) {
            val appContext = context.applicationContext
            builder.dns(
                CampusDns(
                    useVpn = false,
                    enabled = { WbuAuthTransport.isCampusDnsEnabled(appContext) },
                    campusReachable = {
                        WbuNetworkProbe.probeCampusReachableBlocking(appContext)
                    }
                )
            )
        }
        return builder
    }

    /**
     * 只拦「本机回环代理」。
     *
     * Clash / V2Ray / 其它代理 App 在系统里设置的代理几乎都是 `127.0.0.1:port`。
     * 这类代理对校园内网毫无帮助（它拿到的域名会被它自己的规则送去公网映射），
     * 反而把能直连的内网地址挡掉。
     */
    private class LoopbackProxyBypass(
        private val delegate: ProxySelector?
    ) : ProxySelector() {

        override fun select(uri: URI): List<Proxy> {
            val proxies = runCatching { delegate?.select(uri) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: return listOf(Proxy.NO_PROXY)
            return if (proxies.all(::isLoopback)) {
                Log.i(TAG, "系统代理是本机回环代理，${uri.host} 改走直连")
                listOf(Proxy.NO_PROXY)
            } else {
                proxies
            }
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
            runCatching { delegate?.connectFailed(uri, sa, ioe) }
        }

        private fun isLoopback(proxy: Proxy): Boolean {
            if (proxy.type() != Proxy.Type.HTTP) return false
            val address = proxy.address() as? InetSocketAddress ?: return false
            return WbuNetworkPolicy.isLoopbackProxyHost(address.hostString)
        }
    }
}
