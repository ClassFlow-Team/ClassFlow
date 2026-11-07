package com.shiro.classflow.data.network.wbu

import android.content.Context
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.net.URLEncoder

/**
 * CAS 换票（`/authserver/login?service=…`）的通道规划与执行。
 *
 * 背景（2026-11-08 真机实测）：校外直连 `ids.wbu.edu.cn` 的换票那一跳会被云 WAF 回 `403` +
 * 7 KB 的 websaas 挡板页；同一跳在 WebVPN 隧道里是好的（`302` + `ticket=ST-…`）。
 * 过去这里用的是 [WbuAuthTransport.idsBase]（只看「统一认证经过 WebVPN」开关，默认关），
 * 于是「内容走隧道、换票走公网」—— 移动门户 / 图书借阅 / 座位预约在校外全部打不开。
 *
 * 本类把判据收敛成一条：**内容走哪条通道，换票就走哪条通道**（见 [ticketBaseFor]）。
 *
 * 三块内容：纯决策函数（[ticketBaseFor]）+ 纯解析函数（[casLoginUrlFor] / [ticketLanding]）
 * + 实际执行器（[fetchLanding]）。前两块不碰 Android / 网络，可以直接用 JVM 单测钉住。
 */
internal object CasTicketExchange {

    /** 一次换票尝试的结局。 */
    sealed interface Outcome {
        /** 换到了票：`landingUrl` 是 CAS 回跳的完整落地地址，含 `?ticket=ST-…`。 */
        data class Ticket(val landingUrl: String) : Outcome

        /** 拿到响应但没有 `ticket=`（典型：公网那个 301 到 `login/`、或登录页 200）。 */
        data object NoTicket : Outcome

        /** 被云 WAF 挡板页拦下（正文含 websaas / XWD-Token）。 */
        data class Blocked(val statusCode: Int) : Outcome

        /** 被 WebVPN 门户接管（门禁 TWFID 掉了，`Location` 指向 `webvpn.wbu.edu.cn`）。 */
        data object PortalTakeover : Outcome

        /** 一个响应都没拿到。 */
        data class Unreachable(val cause: Throwable) : Outcome
    }

    /**
     * 换票该用哪个 ids 基址。**纯函数，单测钉住。**
     *
     * 只有一条规则：**内容走哪条通道，换票就走哪条通道**；那个设置开关仅用于「用户显式强制走隧道」。
     *
     * 刻意**没有**「公网失败 → 改走隧道」的自动回落：内容走直连时把票据换成隧道宿主，
     * 会让「票据的宿主」与「内容的通道」不一致（两级代理子域的 TLS 主机名也不匹配），
     * 而且会把真实原因（网线 / 校园网不通）掩盖成一次莫名的换通道。直连失败时
     * UI 本来就有「改用 WebVPN」按钮（见 `AccessAction.UseWebVpnOnce`），交给用户决定。
     *
     * @param contentViaVpn 这次内容走哪条通道（`WebAppViewModel` 的 useVpn / `WbuQueryClient.useVpn`）
     * @param idsViaWebVpn 用户设置里的「统一认证经过 WebVPN」（显式强制）
     */
    fun ticketBaseFor(
        publicBase: String,
        proxyBase: String,
        contentViaVpn: Boolean,
        idsViaWebVpn: Boolean,
    ): String = if (contentViaVpn || idsViaWebVpn) proxyBase else publicBase

    /**
     * 构造换票入口 URL。
     *
     * **必须不带尾斜杠**：`/authserver/login/` 在公网是 `403` 挡板页、在隧道里是 `404`；
     * 只有 `/authserver/login?service=…` 这种形态两边都认。
     */
    fun casLoginUrlFor(base: String, serviceUrl: String): String =
        "${base.trimEnd('/')}/authserver/login?service=${URLEncoder.encode(serviceUrl, "UTF-8")}"

    /**
     * 从 `Location` 里取「含 ticket 的落地地址」；没有票据（301 / 登录页 / 空）返回 null。
     *
     * 判据是**未解码的原始 URL 里出现 `ticket=`**，不要换成 `queryParameter("ticket")`：
     * 公网那个 301 的 `service=` 值里也可能带一个编码形态的 `ticket%3D`，
     * 按解码后的 query 判会把「被拦截」误判成「有票」。
     */
    fun ticketLanding(location: String?): String? {
        val loc = location?.trim().orEmpty()
        if (loc.isEmpty()) return null
        if (!loc.contains("ticket=")) return null
        return loc.takeIf { it.toHttpUrlOrNull() != null }
    }

    /**
     * 换一张票，返回**含 `ticket=` 的落地地址**（不做宿主改写，调用方按自己通道决定）。
     *
     * 通道由 [contentViaVpn] 决定 —— 校外内容走隧道时换票也必须在隧道里，
     * 否则公网那一跳会被云 WAF 回 403 挡板页，全场只剩一句「登录状态已过期」。
     *
     * @throws WbuSessionExpiredException 本地没有 CASTGC / 没换出票 / 门禁失效
     * @throws WafBlockedException 换票那一跳被云 WAF 拦（交给上层 `AccessFailure.Intercepted`）
     * @throws java.io.IOException 一个响应都没拿到（交给上层 `AccessFailure.Unreachable`）
     *
     * 阻塞调用：调用方必须已经在 `Dispatchers.IO` 上。
     */
    fun fetchLanding(context: Context, serviceUrl: String, contentViaVpn: Boolean): String {
        val direct = WbuAuthTransport.getShared(context, false)
        val vpn = WbuAuthTransport.getShared(context, true)
        // 刻意**不**在这里 restoreCookieStore()：三个调用方（WbuWebAppClient.prepareSession /
        // WbuQueryClient.ensureOpacSession / CredentialVerifier）各自入口都已经恢复过；
        // 在这里再恢复一次会先 clear 再回填磁盘快照，可能把刚写进内存的 TWFID / 新 Cookie 冲掉。
        val cookieStore = direct.cookieStore   // 进程级共享，两个 transport 看到的是同一份

        val hasTgc = cookieStore.any { it.name == "CASTGC" && it.value.isNotBlank() }
        if (!hasTgc) {
            Log.w(TAG, "本地没有 CASTGC，无法换票：$serviceUrl")
            throw WbuSessionExpiredException(AccessLayer.UnifiedAuth)
        }

        val proxyBase = vpn.idsProxyBaseUrl()
        val base = ticketBaseFor(
            publicBase = direct.idsPublicBase,
            proxyBase = proxyBase,
            contentViaVpn = contentViaVpn,
            idsViaWebVpn = WbuAuthTransport.getIdsViaWebVpn(context),
        )
        val viaTunnel = base == proxyBase

        // 走隧道前把门禁票注入内存 Cookie 罐：代理子域需要 `TWFID` 才不会被门户接管。
        // 只注入不探活（探活是 `WebVpnClient.probeTwfid` 的事，这里多打一次请求没必要）。
        if (viaTunnel) {
            val twfid = WbuAuthTransport.getTwfid(context)
            if (twfid.isNotBlank()) WebVpnClient(vpn).injectTwfid(twfid)
        }

        // 代理基址必须用 vpn transport：`ids-wbu-edu-cn.webvpn.wbu.edu.cn` 是两级子域，
        // 只有 vpn transport 装了「wbu 域名族」的 hostnameVerifier，直连 transport 会握手失败。
        val hopTransport = if (viaTunnel) vpn else direct
        return when (val outcome = attempt(hopTransport, casLoginUrlFor(base, serviceUrl))) {
            is Outcome.Ticket -> {
                Log.i(TAG, "换票成功（tunnel=$viaTunnel）：$serviceUrl")
                outcome.landingUrl
            }
            Outcome.NoTicket -> {
                Log.w(TAG, "换票没有拿到票据（base=$base）：$serviceUrl")
                throw WbuSessionExpiredException(AccessLayer.UnifiedAuth)
            }
            is Outcome.Blocked -> {
                Log.w(TAG, "换票被 WAF 拦（base=$base, code=${outcome.statusCode}）：$serviceUrl")
                throw WafBlockedException(base.toHttpUrlOrNull()?.host, outcome.statusCode)
            }
            // 门禁失效：上层会去重建 TWFID，而不是白让用户重登统一认证
            Outcome.PortalTakeover -> {
                Log.w(TAG, "换票被 WebVPN 门户接管（门禁失效）：base=$base")
                throw WbuSessionExpiredException(AccessLayer.WebVpnPortal)
            }
            is Outcome.Unreachable -> {
                Log.w(TAG, "换票连不上（base=$base）：$serviceUrl", outcome.cause)
                throw outcome.cause
            }
        }
    }

    /**
     * 一次尝试：`followRedirects = false` 手动跟最多 [MAX_HOPS] 跳，边跟边找 `ticket=`。
     *
     * 为什么必须手动跟：公网 `/authserver/login?service=…` 先回 `301` 到带尾斜杠的地址，
     * 那个地址才是 `403`。只看第一跳会得到「无票据」，把「被 WAF 拦」误判成「会话过期」——
     * 这正是本次事故里「统一身份认证登录状态已过期」的来源。
     */
    private fun attempt(transport: WbuAuthTransport, startUrl: String): Outcome {
        val noRedirect = transport.client.newBuilder().followRedirects(false).build()
        var url = startUrl
        var hops = 0
        while (hops < MAX_HOPS) {
            hops++
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .get()
                .build()
            val hop = try {
                noRedirect.newCall(req).execute().use { resp ->
                    Hop(
                        code = resp.code,
                        location = resp.header("Location"),
                        contentType = resp.header("Content-Type"),
                        body = resp.body.string(),
                    )
                }
            } catch (e: WafBlockedException) {
                // 传输层的 WafBlockInterceptor 已经认出来了
                return Outcome.Blocked(e.statusCode)
            } catch (e: Exception) {
                return Outcome.Unreachable(e)
            }

            ticketLanding(hop.location)?.let { return Outcome.Ticket(it) }
            if (WafBlockDetector.looksLikeWafBlock(hop.code, hop.contentType, hop.body)) {
                return Outcome.Blocked(hop.code)
            }
            // 门户接管：门禁 TWFID 掉了，不是换票问题 —— 必须分开报，
            // 上层据此去重建门禁，而不是白让用户重登统一认证。
            if (hop.location.orEmpty().contains("/por/")) return Outcome.PortalTakeover

            if (hop.code in 300..399 && !hop.location.isNullOrBlank()) {
                url = url.toHttpUrlOrNull()?.resolve(hop.location)?.toString() ?: hop.location
                continue
            }
            return Outcome.NoTicket
        }
        return Outcome.NoTicket
    }

    /** 一跳响应的只读快照。 */
    private class Hop(
        val code: Int,
        val location: String?,
        val contentType: String?,
        val body: String,
    )

    private const val TAG = "CasTicketExchange"

    /** CAS 换票实测最多 2 跳（301 → 302），留一倍余量防环。 */
    private const val MAX_HOPS = 4
}
