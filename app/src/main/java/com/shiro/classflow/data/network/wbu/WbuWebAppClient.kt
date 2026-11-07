package com.shiro.classflow.data.network.wbu

import com.xingheyuzhuan.shiguangschedule.R

import android.content.Context
import android.util.Log
import com.shiro.classflow.data.model.wbu.WebAppDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * 通用网页应用客户端：负责与统一认证 (CAS) 及业务系统的 SSO 回调服务交互，
 * 获取用于进入业务前端的有效 JWT 凭证，并构造承载 Token 的完整全屏 WebView 访问 URL。
 */
class WbuWebAppClient(
    private val context: Context,
    val useVpn: Boolean = false
) {
    private val transport: WbuAuthTransport = WbuAuthTransport.getShared(context, useVpn)
    private val client = transport.client

    /**
     * 计算该网页应用在当前网络通道（直连或 WebVPN）下的基础基址（不含结尾斜杠）。
     */
    fun resolveBaseUrl(def: WebAppDefinition): String {
        return if (useVpn) {
            transport.webVpnProxyBase(def.targetHost, withSingleSuffix = def.vpnSingleSuffix)
        } else {
            def.realOrigin
        }
    }

    /**
     * 为指定的网页应用从 CAS 和 SSO 换取一次性回跳 Token (JWT)。
     *
     * 流程：
     * 1. 检查并恢复 Cookie 库，必须持有有效的统一身份认证凭证 CASTGC，否则抛出 [WbuSessionExpiredException]；
     * 2. (若定义) 请求业务系统的 SSO 入口（如 /rem/static/sso/login），建立与业务 SSO 网关的会话 (rem_JSESSIONID)；
     * 3. 请求 CAS 认证服务核验 CASTGC 并申请目标业务 Service 的 ST ticket；
     * 4. 携带 ST ticket 访问业务系统的核销回调（如 /rem/static/sso/webOAuthRed?ticket=ST-xxx）；
     *    在 WebVPN 环境下需将 Location 重定向目标 Host 映射为 WebVPN 代理宿主；
     * 5. 捕获重定向结果并解析提取 `token` 参数 (JWT)。
     *
     * @throws WbuSessionExpiredException 当凭据缺失或已过期时抛出
     * @throws IOException 当网络通信失败或返回异常时抛出
     */
    suspend fun fetchCasCallbackToken(def: WebAppDefinition): String = withContext(Dispatchers.IO) {
        prepareSession()

        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val currentBase = resolveBaseUrl(def)

        // 1. 若配置了 SSO 前置入口，先访问以在业务网关建立会话 (如 rem_JSESSIONID)
        val ssoPath = def.remSsoLoginPath
        if (!ssoPath.isNullOrBlank()) {
            val vueServiceUrl = "${def.realOrigin}${def.appPath}#/login"
            val ssoInitUrl = "$currentBase$ssoPath?redirectUrl=${URLEncoder.encode(vueServiceUrl, "UTF-8")}"
            val ssoReq = Request.Builder()
                .url(ssoInitUrl)
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .get()
                .build()

            runCatching {
                noRedirectClient.newCall(ssoReq).execute().use { resp ->
                    val loc = resp.header("Location").orEmpty()
                    Log.d("WbuWebAppClient", "SSO init resp: ${resp.code}, Location: $loc")
                    if (loc.contains("/por/login") || loc.contains("/por/login_psw") || loc.contains("/portal/")) {
                        throw WbuSessionExpiredException(AccessLayer.WebVpnPortal)
                    }
                }
            }.getOrThrow()
        }

        // 2. 向 CAS 申请目标业务服务的 Service Ticket (ST)
        // 注意：CAS 服务端登记的 service 验证地址始终必须是业务端声明的公网真实 URL
        val casLocation = requestCasTicket(def.casServiceUrl)

        // 3. 将 CAS 回跳的目标重写为当前通道宿主（WebVPN 下重写为代理宿主）
        val targetCallbackUrl = if (useVpn) {
            rewriteToProxyHost(casLocation, def)
        } else {
            casLocation
        }

        Log.i("WbuWebAppClient", "Accessing SSO callback: $targetCallbackUrl")

        // 4. 访问核销地址，捕获签发出来的 JWT Location
        val callbackReq = Request.Builder()
            .url(targetCallbackUrl)
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .get()
            .build()

        val finalLocation = noRedirectClient.newCall(callbackReq).execute().use { resp ->
            resp.header("Location")
        }

        if (finalLocation.isNullOrBlank()) {
            Log.e("WbuWebAppClient", "SSO callback returned no redirect Location")
            throw IOException(context.getString(R.string.error_sso_callback_invalid))
        }

        if (finalLocation.contains("/por/") || finalLocation.contains("webvpn.wbu.edu.cn/por")) {
            Log.w("WbuWebAppClient", "SSO callback redirected to WebVPN portal: $finalLocation")
            throw WbuSessionExpiredException(AccessLayer.WebVpnPortal)
        }

        // 5. 从重定向 URL 解析提取 token
        val token = extractTokenFromUrl(finalLocation)
        if (token.isNullOrBlank()) {
            if (finalLocation.contains("login")) {
                throw WbuSessionExpiredException(AccessLayer.UnifiedAuth)
            }
            Log.e("WbuWebAppClient", "Failed to extract token from Location: $finalLocation")
            throw WbuSessionExpiredException(AccessLayer.UnifiedAuth)
        }

        transport.persistCookieStore()
        Log.i("WbuWebAppClient", "Successfully obtained WebApp token for ${def.id}")
        token
    }

    /**
     * 只要一张 CAS 票据、**不在客户端核销**：返回 CAS 回跳的落地地址（含 `?ticket=ST-…`）。
     *
     * 供 [WebAppLaunchMode.CAS_SERVICE_TICKET] 的门户类应用使用：票据交给 WebView 加载，
     * 由业务端自己核销并下发自己的会话 Cookie —— 会话落在 WebView 的 Cookie 罐里，
     * 页面后续的跳转与 XHR 才带着它。前提是本机持有有效的 `CASTGC`（没有就抛
     * [WbuSessionExpiredException]，由上层走静默登录 / 登录面板）。
     *
     * @throws WbuSessionExpiredException 凭据缺失或已过期（含 WebVPN 门禁失效）
     */
    suspend fun fetchCasServiceTicketUrl(def: WebAppDefinition): String = withContext(Dispatchers.IO) {
        prepareSession()
        val landing = requestCasTicket(def.casServiceUrl)
        if (useVpn) rewriteToProxyHost(landing, def) else landing
    }

    /**
     * 「CAS 票据桥」：给任意业务系统地址换一张票据，返回落地地址（`<service>?ticket=ST-…`）。
     *
     * 门户里的应用（ehall / 新生入馆教育 …）自己跳 CAS 登录页属于正常流程，
     * 用本机 `CASTGC` 直接替它们走完这一步即可，用户不用再登录一次。
     *
     * @param proxyHostOf 非空时，落地地址的宿主会被重写为该定义的 WebVPN 代理宿主（当前通道为 WebVPN）；
     *                    为 null 时原样返回（跨站应用在 WebVPN 下按现状原样加载，见研究报告 §7 风险表）。
     */
    suspend fun resolveCasServiceRedirect(
        serviceUrl: String,
        proxyHostOf: WebAppDefinition? = null
    ): String = withContext(Dispatchers.IO) {
        prepareSession()
        val landing = requestCasTicket(serviceUrl)
        if (useVpn && proxyHostOf != null) rewriteToProxyHost(landing, proxyHostOf) else landing
    }

    /**
     * 恢复 Cookie 库、并把 WebVPN 门禁通行证注入内存 Cookie 罐。
     */
    private fun prepareSession() {
        transport.restoreCookieStore()

        // 若处于 WebVPN 模式，确保将 TWFID 门禁 Cookie 注入到 OkHttp 的 cookieStore 内存中
        if (useVpn) {
            val twfid = WbuAuthTransport.getTwfid(context)
            if (twfid.isNotBlank()) {
                WebVpnClient(transport).injectTwfid(twfid)
            }
        }
    }

    /**
     * 请求 CAS 为 [serviceUrl] 签发 Service Ticket，返回 CAS 回跳的完整落地地址。
     *
     * 通道由**本次内容通道**（[useVpn]）决定，见 [CasTicketExchange]：校外内容走隧道时，
     * 换票也必须在隧道里换，否则公网那一跳会被云 WAF 回 403 挡板页。
     * 票据留在地址里不被消费 —— 谁核销由调用方决定。
     *
     * @throws WbuSessionExpiredException 凭据缺失 / 没换出票 / WebVPN 门禁失效
     * @throws WafBlockedException 换票那一跳被云 WAF 拦
     * @throws IOException 网络不可用
     */
    private fun requestCasTicket(serviceUrl: String): String {
        // CasTicketExchange 内部已经处理了：CASTGC 缺失、无票据、门禁接管（会抛
        // WbuSessionExpiredException(WebVpnPortal)）、被 WAF 拦、连不上。
        return CasTicketExchange.fetchLanding(
            context = context,
            serviceUrl = serviceUrl,
            contentViaVpn = useVpn,
        ).trim()   // Location 头偶尔带尾随空格（实测 `…/ydmh%20` 被 Tomcat 直接 404）
    }

    /**
     * 将公网 URL 的 Host 重写为对应的 WebVPN 代理宿主，保留其路径与 Query。
     */
    private fun rewriteToProxyHost(urlStr: String, def: WebAppDefinition): String {
        val parsed = urlStr.toHttpUrlOrNull() ?: return urlStr
        val proxyBase = transport.webVpnProxyBase(def.targetHost, withSingleSuffix = def.vpnSingleSuffix).toHttpUrlOrNull()
            ?: return urlStr
        return parsed.newBuilder()
            .scheme(proxyBase.scheme)
            .host(proxyBase.host)
            .port(proxyBase.port)
            .build()
            .toString()
    }

    /**
     * 模拟前端 Fe(href) 逻辑：提取 `?` 与 `#` 之间的 query 参数，或者 URL 中的 `token` 参数。
     */
    fun extractTokenFromUrl(url: String): String? {
        val queryPart = (url.split("?").getOrNull(1) ?: "").split("#")[0]
        if (queryPart.isNotBlank()) {
            val map = queryPart.split("&").mapNotNull { pair ->
                val parts = pair.split("=")
                if (parts.size >= 2) parts[0] to parts.subList(1, parts.size).joinToString("=") else null
            }.toMap()
            map["token"]?.takeIf { it.isNotBlank() }?.let { return it }
        }

        // 兜底正则匹配
        return Regex("""[?&]token=([^&#]+)""").find(url)?.groupValues?.getOrNull(1)
    }

    /**
     * 构造供 WebView 直接打开的全屏页面 URL（注入 ?token=xxx 并挂接默认 Hash 路由）。
     */
    fun buildLaunchUrl(def: WebAppDefinition, token: String): String {
        val base = resolveBaseUrl(def)
        val path = if (def.appPath.startsWith("/")) def.appPath else "/${def.appPath}"
        val normalizedPath = if (path.endsWith("/")) path else "$path/"
        val hash = def.defaultHash.takeIf { it.isNotBlank() } ?: ""
        return "$base$normalizedPath?token=$token$hash"
    }
}
