package com.shiro.classflow.data.network.wbu

import com.xingheyuzhuan.shiguangschedule.R

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 一卡通移动服务平台（新中新 berserker / 慧新e校）数据与鉴权客户端。
 *
 * 基础基址：http://yktfwpt.wbu.edu.cn
 *
 * 核心机制（基于实测研究笔记 ykt_notes.md）：
 * 1. 平台支持公网直连（无需 WebVPN 或校园网）；
 * 2. 鉴权采用金智 CAS OAuth2.0，利用统一身份认证 CASTGC 免密换取平台 JWT 令牌；
 * 3. 平台对同一账号为单活跃会话策略（最后登录者有效），但使用 refresh_token 刷新不会顶号；
 * 4. 业务访问携带 synjones-auth: bearer <access_token>。
 */
class WbuCampusCardClient(
    private val context: Context,
    val useVpn: Boolean = false
) {
    private val transport: WbuAuthTransport = WbuAuthTransport.getShared(context, useVpn)
    private val client = transport.client

    companion object {
        const val TAG = "WbuCampusCardClient"
        const val BASE_URL = "http://yktfwpt.wbu.edu.cn"
        const val RESULT_PATH = "/plat/?name=loginTransit"
        const val OAUTH_BASIC = "bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0"
        const val CAS_CLIENT_ID = "1524429607647793152"

        /** 统一认证的公网主机名。校外直连它会先撞学校的云 WAF，见 [WafBlockedException]。 */
        const val IDS_PUBLIC_HOST = "ids.wbu.edu.cn"

        /**
         * 平台「校园码 / 付款码」内页路径。
         *
         * 页面用 history 路由（`base = /plat/`），完整地址是 `/plat/campusCode?synjones-auth=<token>`；
         * 原生页面在「脱机码 / 接口异常」时回落到这一页，见 [buildLaunchUrl]。
         */
        const val PATH_CAMPUS_CODE = "campusCode"

        /**
         * 换票链路总跳数上限。实测正常换票是 4~6 跳（含授权确认页），15 跳留足余量；
         * 之所以要硬上限，是因为服务端一旦返回环形重定向，没有上限就会一直转下去。
         */
        private const val MAX_HOPS = 15

        /** 401/403/5xx 的退避间隔：`authorize` 的抖动是秒级的，800ms 足够跨过去。 */
        private const val TRANSIENT_RETRY_DELAY_MS = 800L

        /**
         * 取令牌单飞：**进程级**，跨实例共享同一次「校验 → 刷新 → 换票」全过程。
         *
         * 必须是进程级而不是每个 client 一份：这个类在 16 处被 `new` 出来（取水页、付款码、
         * 网页容器、扫一扫、洗浴、吹风机…），各建各的实例。HAR 里 6 秒 3 轮令牌请求就是
         * 多个页面同时进来，实例级单飞根本拦不住。
         */
        private val tokenFlight = SingleFlight<String>()

        /** 换票单飞：进程级共享同一次 CASTGC 换票，避免多个入口互相顶号。 */
        private val ssoFlight = SingleFlight<TokenResult>()
    }

    data class TokenResult(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Long,
        val sno: String?,
        val name: String?,
        val uuid: String?
    )

    /**
     * 确保获取到有效的一卡通平台 access_token。
     * 1. 检查已保存的 access_token 是否仍有效；
     * 2. 若失效，优先尝试 refresh_token 刷新（不顶号）；
     * 3. 若无 refresh_token 或刷新失败，使用统一身份认证 CASTGC 重新换票。
     *
     * 整个过程走单飞：HAR A 里 6 秒内打了 3 轮令牌接口，正是多个入口同时进来各自刷新的结果。
     * 串行化只能让人排队，而去重能让第二个入口直接用第一个的结果，连刷新都不必再发。
     */
    suspend fun ensureValidAccessToken(): String = tokenFlight.run { ensureValidAccessTokenFlow() }

    /** [ensureValidAccessToken] 的实际实现；并发去重在外层单飞里。 */
    private suspend fun ensureValidAccessTokenFlow(): String = withContext(Dispatchers.IO) {
        var token = WbuAuthTransport.getCampusCardAccessToken(context)
        val tokenValid = if (token.isNotBlank()) checkSession(token) else false
        if (tokenValid) return@withContext token

        val refreshToken = WbuAuthTransport.getCampusCardRefreshToken(context)
        if (refreshToken.isNotBlank()) {
            val refreshed = refreshToken(refreshToken)
            if (refreshed != null && refreshed.accessToken.isNotBlank()) {
                WbuAuthTransport.setCampusCardTokens(context, refreshed.accessToken, refreshed.refreshToken)
                return@withContext refreshed.accessToken
            }
        }

        val loginRes = loginWithCasTgc()
        WbuAuthTransport.setCampusCardTokens(context, loginRes.accessToken, loginRes.refreshToken)
        loginRes.accessToken
    }

    /**
     * 解析平台第三方子应用的真实启动地址（带一次性 ticket 票据）。
     * 例：appId=59 -> U净饮水机；appId=45 -> U净洗衣机。
     */
    suspend fun resolveAppLaunchUrl(appId: Int, accessToken: String): String? = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext null
        try {
            val noRedirectClient = client.newBuilder().followRedirects(false).build()
            val url = "$BASE_URL/berserker-base/redirect?appId=$appId&loginFrom=h5&synAccessSource=h5&type=app&synjones-auth=$accessToken"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .header("synAccessSource", "h5")
                .get()
                .build()

            noRedirectClient.newCall(req).execute().use { resp ->
                resp.header("Location")
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveAppLaunchUrl failed for appId=$appId", e)
            null
        }
    }

    /**
     * 校验当前平台 access_token 是否仍然有效（非 401 已经在其他设备登录）。
     */
    suspend fun checkSession(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext false
        try {
            val req = Request.Builder()
                .url("$BASE_URL/berserker-base/user?refresh=1")
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .header("synjones-auth", "bearer $accessToken")
                .header("synAccessSource", "h5")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) return@withContext false
                val bodyStr = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(bodyStr) }.getOrNull()
                json?.optInt("code") == 200
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkSession failed", e)
            false
        }
    }

    /**
     * 使用 refresh_token 刷新获取新的 access_token（不会顶掉其他设备！）。
     */
    suspend fun refreshToken(refreshToken: String): TokenResult? = withContext(Dispatchers.IO) {
        if (refreshToken.isBlank()) return@withContext null
        try {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("scope", "all")
                .add("logintype", "sso")
                .build()

            val req = Request.Builder()
                .url("$BASE_URL/berserker-auth/oauth/token")
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .header("Authorization", "Basic $OAUTH_BASIC")
                .header("synAccessSource", "h5")
                .post(form)
                .build()

            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (resp.code != 200) {
                    Log.w(TAG, "refreshToken returned HTTP ${resp.code}: $bodyStr")
                    return@withContext null
                }
                val json = JSONObject(bodyStr)
                val token = json.optString("access_token")
                if (token.isNullOrBlank()) return@withContext null

                TokenResult(
                    accessToken = token,
                    refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken,
                    expiresIn = json.optLong("expires_in", 6047999),
                    sno = json.optString("sno").takeIf { it.isNotBlank() },
                    name = json.optString("name").takeIf { it.isNotBlank() },
                    uuid = json.optString("uuid").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshToken exception", e)
            null
        }
    }

    /**
     * 主动向平台发起登出请求（使服务端作废该 access_token 会话）。
     */
    suspend fun logout(accessToken: String) = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext
        try {
            val url = "$BASE_URL/berserker-base/redirect?type=logout&synjones-auth=$accessToken&loginFrom=h5&synAccessSource=h5"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .get()
                .build()

            client.newBuilder().followRedirects(false).build().newCall(req).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "logout exception", e)
        }
    }

    /**
     * 平台应用（服务端配置出来的宫格应用，如「账户充值」「电费」「网费」）。
     *
     * 平台把每个功能都注册成一个应用，并给每个应用配了 H5 深链（[website]）——
     * 这就是「点进某个功能页」的正路，不必让用户自己在平台首页里翻。
     */
    data class PlatformApp(
        /** 应用 id（`bh`）：`/berserker-base/redirect?appId=` 用的就是它。 */
        val id: Int,
        /** 应用编码（如 `pay-code` / `card-recharge` / `balance`）：语义稳定，按它匹配最合适。 */
        val appCode: String,
        /** 应用名称（如「账户充值」），仅供日志与兜底展示。 */
        val name: String,
        /** H5 深链（`website`），如 `/campus-card/?name=balanceInquiry`。 */
        val website: String?
    )

    /**
     * 拉取平台应用表（实测 22 个应用）。
     *
     * ```
     * GET {base}/berserker-app/app/getAllApps?platType=h5&userType=user
     * Header: synjones-auth: bearer <access_token>；synAccessSource: h5
     * → {"code":200,"data":[{"bh":27,"appCode":"card-recharge","appName":"账户充值","website":"/campus-card/?name=…",…}]}
     * ```
     *
     * 抛异常（网络 / 401 / 解析失败）由调用方兜底成「固定深链」或平台首页 —— 应用表拿不到
     * 不该让整个改道失败。
     */
    suspend fun listApps(accessToken: String): List<PlatformApp> = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext emptyList()
        val req = Request.Builder()
            .url("$BASE_URL/berserker-app/app/getAllApps?platType=h5&userType=user")
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .header("synjones-auth", "bearer $accessToken")
            .header("synAccessSource", "h5")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.code != 200) {
                Log.w(TAG, "getAllApps returned HTTP ${resp.code}: $body")
                return@withContext emptyList()
            }
            val array = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
                ?: return@withContext emptyList()
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val code = item.optString("appCode").takeIf { it.isNotBlank() } ?: continue
                    add(
                        PlatformApp(
                            id = item.optInt("bh", -1),
                            appCode = code,
                            name = item.optString("appName"),
                            website = item.optString("website").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
    }

    /**
     * 把平台应用的 `website` 深链拼成**免密直达**的完整地址。
     *
     * 平台前端（`/plat/`、`/campus-card/`、`/charge-app/`）统一按
     * `getRequest(location.href)["synjones-auth"] || sessionStorage.access_token` 取令牌，
     * 所以地址上带一份 `synjones-auth=<access_token>` 就等价于「已登录」。
     *
     * @param website 应用表里的 `website`，形如 `/campus-card/?name=balanceInquiry`；
     *                以 `http` 开头时按绝对地址使用（U净 那类外部 H5）。
     */
    fun buildDeepLinkUrl(accessToken: String, website: String): String {
        val path = website.trim()
        val absolute = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "$BASE_URL${if (path.startsWith("/")) "" else "/"}$path"
        }
        val separator = if (absolute.contains("?")) "&" else "?"
        return "$absolute$separator" + "synjones-auth=" + URLEncoder.encode(accessToken, "UTF-8")
    }

    /**
     * 按 appCode 解析应用深链：`appCode → 应用表 → website → 深链`。
     *
     * 接口异常、拿到空表、没匹配到该 appCode、或该应用没配 `website` 时都返回 null，
     * 由调用方回落到规则里写死的固定深链。
     */
    suspend fun resolveDeepLink(accessToken: String, appCode: String): String? {
        if (accessToken.isBlank() || appCode.isBlank()) return null
        val app = runCatching { listApps(accessToken) }.getOrElse {
            Log.w(TAG, "listApps failed while resolving appCode=$appCode", it)
            return null
        }.firstOrNull { it.appCode.equals(appCode, ignoreCase = true) }
        val website = app?.website ?: return null
        return buildDeepLinkUrl(accessToken, website)
    }

    /**
     * 使用统一身份认证 (CASTGC) 免密换取一卡通移动服务平台 Token。
     *
     * **并发去重**：同一时刻只跑一条换票链路，后来者等同一个结果（失败也共享同一个异常）。
     * 起因是 HAR C：打水页、同步引擎、网页容器各发起一次，三条链路拿着同一个 CASTGC 同时换票，
     * 而平台是「单活跃会话」策略，彼此顶号后回「已经在其他设备登录」—— 用户看到的就是
     * 「明明刚登录，打水还是失败」。串行化只是让人排队，去重才能让它们复用同一次结果。
     */
    suspend fun loginWithCasTgc(): TokenResult = ssoFlight.run { loginWithCasTgcFlow() }

    /**
     * 真正的换票实现（[loginWithCasTgc] 的串行段）。并发去重与结果共享在外层。
     *
     * 遵循 ykt_notes.md 的 CAS OAuth2 链路：
     * 1. GET /berserker-auth/cas/oauth2?resultUrl=...
     * 2. CAS oauth2.0/authorize -> login?service=... (带 CASTGC 换 ST)
     * 3. oauth2.0/callbackAuthorize -> 若首次需要则提交授权确认页 -> 重定向回 /cas/oauth2url 换取 ticket
     * 4. POST /berserker-auth/oauth/token (username=ticket, password=ticket, grant_type=password, logintype=sso)
     */
    private suspend fun loginWithCasTgcFlow(): TokenResult = withContext(Dispatchers.IO) {
        transport.restoreCookieStore()

        val hasTgc = transport.cookieStore.any { it.name == "CASTGC" && it.value.isNotBlank() }
        if (!hasTgc) {
            throw WbuSessionExpiredException(AccessLayer.UnifiedAuth)
        }

        val noRedirectClient = client.newBuilder().followRedirects(false).build()

        val startUrl = "$BASE_URL/berserker-auth/cas/oauth2?resultUrl=${URLEncoder.encode(BASE_URL + RESULT_PATH, "UTF-8")}"
        var currentUrl = startUrl
        var ticket: String? = null
        var consentHandled = false

        // 校外直连 ids 会撞学校前面的云 WAF（实测回一张 404 拦截页）。这条链路以前完全不看
        // 「统一认证经过WebVPN」开关，于是即便用户开了它，一卡通 / 打水换票照样走公网、照样被拦。
        var viaProxy = WbuAuthTransport.getIdsViaWebVpn(context)
        var wafRetried = false
        // 401/403/5xx 只退避重试一次：这是「授权端点偶发抖动」，不是登录态失效（见 1.5）
        var transientRetried = false

        for (hop in 0 until MAX_HOPS) {
            // currentUrl 始终保留「逻辑地址」（多是 ids 公网地址），发请求时才决定要不要改走隧道：
            // 这样一旦被 WAF 拦下，可以直接把同一跳换成隧道地址重放。
            val hopUrl = if (viaProxy) rewriteIdsHostToProxy(currentUrl) else currentUrl

            val req = Request.Builder()
                .url(hopUrl)
                .header("User-Agent", transport.authUserAgent())
                .header("Accept-Language", transport.authAcceptLanguage)
                .get()
                .build()

            val resp = try {
                noRedirectClient.newCall(req).execute()
            } catch (e: WafBlockedException) {
                // 被 WAF 拦了：换成 WebVPN 隧道把这一跳重放一次。没有门禁票就没得换，原样抛给上层。
                val canRetryOverTunnel = !wafRetried && !viaProxy && transport.currentTwfid().isNotBlank()
                if (!canRetryOverTunnel) throw e
                wafRetried = true
                viaProxy = true
                Log.i(TAG, "ids 直连被 WAF 拦截（host=${e.host}），改用 WebVPN 隧道重放这一跳")
                continue
            }
            val code = resp.code
            val location = resp.header("Location").orEmpty()
            val body = resp.body?.string().orEmpty()
            resp.close()

            ticket = pickTicket(currentUrl, location) ?: pickTicket(hopUrl, location) ?: ticket

            if (code in 300..399 && location.isNotBlank()) {
                val nextUrl = currentUrl.toHttpUrlOrNull()?.resolve(location)?.toString() ?: location
                currentUrl = nextUrl
                continue
            }

            // 检查是否为 OAuth2 授权确认页（首次登录可能需要）
            if (!consentHandled && body.contains("scope") && body.contains("<form")) {
                val posted = trySubmitConsent(body, hopUrl)
                if (posted != null) {
                    consentHandled = true
                    val (postLocation, _) = posted
                    ticket = pickTicket(postLocation) ?: ticket
                    if (postLocation.isNotBlank()) {
                        val nextUrl = currentUrl.toHttpUrlOrNull()?.resolve(postLocation)?.toString() ?: postLocation
                        currentUrl = nextUrl
                        continue
                    }
                }
            }

            if (!ticket.isNullOrBlank()) {
                break
            }

            if (body.contains("pwdFromId") || body.contains("pwdEncryptSalt") || body.contains("authserver/login")) {
                throw WbuSessionExpiredException(AccessLayer.UnifiedAuth)
            }

            // 认证中心拿「已经持有 CASTGC」的请求去 oauth2.0/authorize，实测回了 404
            // （同一个地址在未登录时是 302 到登录页，服务端确实有这个端点）——也就是说
            // 这份 SSO 会话换不出票。归为「登录态不可用」，交给上层用保存的密码重登一次，
            // 而不是把一条 `status=404` 的原始报错甩给用户（取水页过去就是这样卡死的）。
            //
            // 但 401/403/5xx 不能直接这么判：`oauth2.0/authorize` 出现过一次 401、随后连续 20 次
            // 302 的抖动（见 1.5）。一次抖动就把用户顶去重新登录，登录后又立刻撞同一跳，
            // 观感就是「怎么都登不上」。所以先原样退避重试这一跳一次，仍失败才算登录态问题。
            if ((code == 401 || code == 403 || code >= 500) && !transientRetried) {
                transientRetried = true
                Log.i(TAG, "换票第 $hop 跳返回 $code，退避 ${TRANSIENT_RETRY_DELAY_MS}ms 后重试同一跳")
                delay(TRANSIENT_RETRY_DELAY_MS)
                continue
            }

            if (code == 404 || code == 401 || code == 403) {
                throw WbuSessionExpiredException(AccessLayer.UnifiedAuth)
            }

            throw IOException(context.getString(R.string.format_error_campus_card_sso_ticket, currentUrl, code))
        }

        val finalTicket = ticket ?: throw IOException("SSO 重定向链在 $MAX_HOPS 跳内仍未获取到有效票据")

        // 用 ticket 换平台 access_token
        val form = FormBody.Builder()
            .add("username", finalTicket)
            .add("password", finalTicket)
            .add("grant_type", "password")
            .add("scope", "all")
            .add("loginFrom", "h5")
            .add("logintype", "sso")
            .add("device_token", "h5")
            .build()

        val tokenReq = Request.Builder()
            .url("$BASE_URL/berserker-auth/oauth/token")
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .header("Authorization", "Basic $OAUTH_BASIC")
            .header("synAccessSource", "h5")
            .post(form)
            .build()

        val tokenResp = client.newCall(tokenReq).execute()
        val tokenBody = tokenResp.body?.string().orEmpty()
        tokenResp.close()

        val json = runCatching { JSONObject(tokenBody) }.getOrNull()
            ?: throw IOException("换取一卡通令牌失败: $tokenBody")

        val accessToken = json.optString("access_token")
        if (accessToken.isNullOrBlank()) {
            val msg = json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("msg").takeIf { it.isNotBlank() } ?: "未知错误"
            throw IOException("换取一卡通令牌失败: $msg")
        }

        TokenResult(
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
            expiresIn = json.optLong("expires_in", 6047999),
            sno = json.optString("sno").takeIf { it.isNotBlank() },
            name = json.optString("name").takeIf { it.isNotBlank() },
            uuid = json.optString("uuid").takeIf { it.isNotBlank() }
        )
    }

    /**
     * 把指向 ids 公网地址的 URL 改写到 WebVPN 隧道宿主。
     *
     * **只换 scheme + host + port**，路径与查询串原样保留：`service` / `redirect_uri` 里写的
     * 必须是真实公网地址，改了就换不出票。不是 ids 的地址（例如一卡通平台自己的 302 起点）原样返回。
     */
    private fun rewriteIdsHostToProxy(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (!parsed.host.equals(IDS_PUBLIC_HOST, ignoreCase = true)) return url
        val proxy = transport.idsProxyBaseUrl().toHttpUrlOrNull() ?: return url
        val rewritten = parsed.newBuilder()
            .scheme(proxy.scheme)
            .host(proxy.host)
            .port(proxy.port)
            .build()
            .toString()
        if (rewritten != url) {
            // ticket / 票据类参数不进日志
            Log.d(TAG, "routes ids via WebVPN tunnel: ${url.replace(Regex("ticket=[^&]+"), "ticket=***")}")
        }
        return rewritten
    }

    private fun pickTicket(vararg urls: String): String? {
        for (u in urls) {
            if (u.isBlank() || !u.contains("loginTransit")) continue
            val match = Regex("[?&]ticket=([^&]+)").find(u)
            if (match != null) {
                val rawTicket = match.groupValues[1]
                return URLDecoder.decode(rawTicket, "UTF-8")
            }
        }
        return null
    }

    private fun trySubmitConsent(html: String, pageUrl: String): Pair<String, String>? {
        val formMatch = Regex("<form[^>]*action=\"([^\"]+)\"[^>]*>(.*?)</form>", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return null
        val action = formMatch.groupValues[1].replace("&amp;", "&")
        val formBody = formMatch.groupValues[2]

        val formBuilder = FormBody.Builder()
        val inputMatches = Regex("<input[^>]*>").findAll(formBody)
        for (inp in inputMatches) {
            val text = inp.value
            val name = Regex("name=\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: continue
            val value = Regex("value=\"([^\"]*)\"").find(text)?.groupValues?.get(1).orEmpty()
            val type = Regex("type=\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: "text"

            if (type == "hidden" || (type == "checkbox" && text.contains("checked"))) {
                formBuilder.add(name, value)
            }
        }

        val targetUrl = pageUrl.toHttpUrlOrNull()?.resolve(action)?.toString() ?: action
        val req = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", transport.authUserAgent())
            .header("Accept-Language", transport.authAcceptLanguage)
            .header("Referer", pageUrl)
            .post(formBuilder.build())
            .build()

        val resp = client.newBuilder().followRedirects(false).build().newCall(req).execute()
        val loc = resp.header("Location").orEmpty()
        val body = resp.body?.string().orEmpty()
        resp.close()
        return loc to body
    }

    /**
     * 构造供 WebView 打开的一卡通平台 URL（注入 ?synjones-auth=<access_token>）。
     *
     * @param path 平台内页路径（如 `campusCode` = 校园码 / 付款码页）；null 或空 = 平台首页 `?name=` 入口。
     *             平台路由是 history 模式（`base = /plat/`），内页地址形如 `/plat/campusCode`。
     */
    fun buildLaunchUrl(accessToken: String, path: String? = null): String {
        val page = path?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: return "$BASE_URL/plat/?synjones-auth=$accessToken"
        return "$BASE_URL/plat/$page?synjones-auth=$accessToken"
    }

    /**
     * 解析 JWT Payload（学号与姓名）。
     */
    fun decodeJwt(token: String): JSONObject? {
        return runCatching {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val payload = parts[1]
            val padded = payload + "=".repeat((-payload.length) % 4)
            val decodedBytes = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
            JSONObject(String(decodedBytes, Charsets.UTF_8))
        }.getOrNull()
    }
}
