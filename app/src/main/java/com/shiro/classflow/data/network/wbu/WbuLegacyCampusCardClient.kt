package com.shiro.classflow.data.network.wbu

import android.content.Context
import android.util.Log
import com.shiro.classflow.data.model.wbu.LegacyBillCategory
import com.shiro.classflow.data.model.wbu.LegacyCampusCardPage
import com.shiro.classflow.data.model.wbu.LegacyYearTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.net.URLEncoder

/**
 * 旧一卡通平台（`yktyd.wbu.edu.cn:81`）原生账单客户端。
 *
 * 为什么不用新平台：新平台（`yktfwpt`）账单延迟明显，旧平台能拿到当天交易，且详情字段更全。
 * 为什么不用 WebView：旧平台页面自己就在调一套 AJAX JSON 接口，原生直接调同一套接口即可。
 *
 * 链路（每一步都必须带**移动端 UA**）：
 * 1. `CASTGC` → CAS 换票，得到带 `ticket` 的 `/ias/prelogin` 地址；
 * 2. GET 该地址，解析自动提交表单（`ssoticketid` + `continueurl`）；
 * 3. POST `/cassyno/index`，移动 UA 下落到 `/phone/cassyn`，建立旧平台会话；
 * 4. POST `/Page/Page`（`flowID=150`），从页面里解析 `var saccount`；
 * 5. 调 `Report/…` 的 JSON 接口。
 *
 * 强调两点：
 * - `/cassyno/index` 会按 UA 分流，桌面 UA 会落到 PC 版 `/user/user`，后续移动页面全部不可用。
 *   本类的 OkHttp 客户端在 **network interceptor** 里强制覆盖 UA，避免被共享 transport 的
 *   「使用 PC User-Agent」设置改掉。
 * - 旧平台 JSON 即使有数据也会回 `issucceed:false`，不要拿它当成功判据（见解析器）。
 */
class WbuLegacyCampusCardClient(
    private val context: Context,
    private val useVpn: Boolean = false
) {

    private companion object {
        const val TAG = "WbuLegacyCard"

        /** 旧平台真实主机（CAS 下发的 Location、页面里的绝对地址都用它）。 */
        const val LEGACY_HOST = "yktyd.wbu.edu.cn"
    }

    private val transport = WbuAuthTransport.getShared(context, useVpn)

    /** 旧平台两个端口各自的 WebVPN 代理基址（仅 [useVpn] 时用）。 */
    private val proxyPort81 by lazy { transport.webVpnProxyBase(LEGACY_HOST, 81) }
    private val proxyPort8090 by lazy { transport.webVpnProxyBase(LEGACY_HOST, 8090) }

    /**
     * 共享 transport 的 application interceptor 会写入全局 UA 设置；这里再挂一层 network
     * interceptor，保证真正发出去的 `User-Agent` 永远是旧平台移动版。
     *
     * 走 WebVPN 时再加一层 application interceptor：把指向真实旧平台 `yktyd.wbu.edu.cn`
     * 的请求（包括 CAS 302 回跳）改写到对应端口 / 协议的代理宿主。门户前端的编码规则见
     * [WbuAuthTransport.webVpnProxyBase]。
     */
    private val client = transport.client.newBuilder()
        .apply {
            if (useVpn) {
                addInterceptor { chain -> chain.proceed(rewriteToWebVpn(chain.request())) }
            }
        }
        .addNetworkInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", LegacyCampusCardUrls.MOBILE_UA)
                    .build()
            )
        }
        .build()

    /** JSON 接口不跟随重定向：会话失效时服务端会 302 到 `/Phone/Login`。 */
    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 建立会话要跑 4 个请求，必须串行；列表与本月汇总可能同时触发第一次取账号。 */
    private val sessionMutex = Mutex()

    @Volatile
    private var sessionAccount: String? = null

    /** 主动丢弃旧平台会话（刷新 / 登录成功后调用）。 */
    fun forgetSession() {
        sessionAccount = null
    }

    /** 确保拿到旧平台账号；同一实例内复用。 */
    suspend fun ensureSession(): String = withContext(Dispatchers.IO) {
        sessionAccount?.takeIf { it.isNotBlank() } ?: sessionMutex.withLock {
            sessionAccount?.takeIf { it.isNotBlank() } ?: establishSessionOrThrow()
        }
    }

    /** 把「重建后仍被踢回登录页」翻译成业务系统层会话失效，交给 ViewModel 决定是否静默重登。 */
    private fun establishSessionOrThrow(): String = try {
        establishSession()
    } catch (e: LegacySessionExpiredException) {
        throw WbuSessionExpiredException(AccessLayer.Service, e)
    }

    /** 账单：`Report/GetMyBill`。 */
    suspend fun fetchBill(page: Int): LegacyCampusCardPage = withContext(Dispatchers.IO) {
        fetchPage(
            path = "Report/GetMyBill",
            flowId = LegacyCampusCardUrls.FLOW_BILL,
            page = page
        )
    }

    /** 流水：`Report/GetPersonTrjn`（多返回 `ACCTYPENAME` 等字段）。 */
    suspend fun fetchFlow(page: Int): LegacyCampusCardPage = withContext(Dispatchers.IO) {
        fetchPage(
            path = "Report/GetPersonTrjn",
            flowId = LegacyCampusCardUrls.FLOW_FLOW,
            page = page
        )
    }

    /** 月账单分类金额：`report/getamtBypaytype`。 */
    suspend fun fetchMonthCategories(month: String): List<LegacyBillCategory> = withContext(Dispatchers.IO) {
        requestWithSession { account ->
            val body = postJson(
                path = "report/getamtBypaytype",
                params = mapOf("account" to account, "month" to month),
                referer = LegacyCampusCardUrls.billReferer(LegacyCampusCardUrls.FLOW_BILL)
            )
            LegacyCampusCardParser.parseCategories(body)
        }
    }

    /** 某分类的月账单明细：`report/getMonthbillBytype`。 */
    suspend fun fetchMonthRows(month: String, categoryCode: String): LegacyCampusCardPage =
        withContext(Dispatchers.IO) {
            requestWithSession { account ->
                val body = postJson(
                    path = "report/getMonthbillBytype",
                    params = mapOf(
                        "typeid" to categoryCode,
                        "month" to month,
                        "account" to account
                    ),
                    referer = LegacyCampusCardUrls.billReferer(LegacyCampusCardUrls.FLOW_BILL)
                )
                LegacyCampusCardParser.parseEntries(body)
            }
        }

    /** 年度月度汇总：`report/EveryMonthPay`。 */
    suspend fun fetchYearTotals(year: Int): LegacyYearTotals = withContext(Dispatchers.IO) {
        requestWithSession { account ->
            val body = postJson(
                path = "report/EveryMonthPay",
                params = mapOf("account" to account, "year" to year.toString()),
                referer = LegacyCampusCardUrls.billReferer(LegacyCampusCardUrls.FLOW_BILL)
            )
            LegacyCampusCardParser.parseYearTotals(body)
        }
    }

    // ------------------- 会话 -------------------

    /**
     * 完整走一遍：CASTGC → CAS 票据 → prelogin 表单 → `/cassyno/index` → `/Page/Page`。
     *
     * 返回旧平台页面里的 `saccount`，后续所有 `Report/…` 接口都用它。
     */
    private fun establishSession(): String {
        transport.restoreCookieStore()
        val hasTgc = transport.cookieStore.any { it.name == "CASTGC" && it.value.isNotBlank() }
        if (!hasTgc) {
            throw WbuSessionExpiredException(AccessLayer.UnifiedAuth)
        }

        val ticketLocation = requestCasTicket()
        val preLoginForm = requestPreLogin(ticketLocation)
        postCassyno(preLoginForm)
        val account = requestBillPageAccount()
        sessionAccount = account
        Log.i(TAG, "legacy campus card session established")
        return account
    }

    private fun requestCasTicket(): String {
        // 校外直连 ids 会撞 WAF：走 WebVPN 时必须把 CAS 起点换成 ids 的代理宿主
        val idsBase = if (useVpn) transport.idsProxyBaseUrl() else transport.idsPublicBase
        // 过期的 CAS JSESSIONID 会让旧平台的 serviceValidate 失败（500/502），只保留 CASTGC
        transport.forgetCasSessionCookie(idsBase)
        val request = Request.Builder()
            .url(LegacyCampusCardUrls.casLoginUrl(idsBase))
            .get()
            .build()

        noRedirectClient.newCall(request).execute().use { response ->
            val location = response.header("Location").orEmpty()
            if (response.code in 300..399 && location.contains("ticket=", ignoreCase = true)) {
                return location
            }
            Log.w(TAG, "CAS did not return a ticket (HTTP ${response.code})")
            // 没有票据只可能是 CASTGC 失效 / 被 WAF 拦（后者由共享 transport 抛 WafBlockedException）
            throw WbuSessionExpiredException(AccessLayer.UnifiedAuth)
        }
    }

    private fun requestPreLogin(location: String): LegacyCampusCardParser.PreLoginForm {
        val request = Request.Builder()
            .url(location)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            return LegacyCampusCardParser.parsePreLoginForm(body)
                ?: throw WbuSessionExpiredException(AccessLayer.UnifiedAuth)
        }
    }

    private fun postCassyno(form: LegacyCampusCardParser.PreLoginForm) {
        val body = FormBody.Builder()
            .add("errorcode", "1")
            .add("continueurl", form.continueUrl)
            .add("ssoticketid", form.ssoTicketId)
            .build()

        val request = Request.Builder()
            .url(form.continueUrl)
            .header("Referer", LegacyCampusCardUrls.LEGACY_PRELOGIN_ORIGIN)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            // 跟随后应落到 /phone/cassyn；即便没落到，也由下一步 /Page/Page 验证会话
            Log.d(TAG, "legacy CAS landing: ${response.request.url}")
            response.body.string()
        }
    }

    private fun requestBillPageAccount(): String {
        val formBuilder = FormBody.Builder()
        LegacyCampusCardUrls.pageForm(LegacyCampusCardUrls.FLOW_BILL).forEach { (key, value) ->
            formBuilder.add(key, value)
        }

        val request = Request.Builder()
            .url(LegacyCampusCardUrls.pageUrl())
            .header("Referer", LegacyCampusCardUrls.LEGACY_INDEX_REFERER)
            .post(formBuilder.build())
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            val account = LegacyCampusCardParser.parseAccount(body)
            if (account != null) return account

            Log.w(TAG, "bill page has no saccount (final=${response.request.url}, HTTP ${response.code})")
            if (LegacyCampusCardUrls.looksLikeLoginPage(response.request.url.toString(), body)) {
                throw LegacySessionExpiredException()
            }
            throw LegacyCampusCardUnexpectedException("旧一卡通账单页没有返回账号信息")
        }
    }

    // ------------------- 请求与会话恢复 -------------------

    private suspend fun fetchPage(path: String, flowId: Int, page: Int): LegacyCampusCardPage =
        requestWithSession { account ->
            val body = postJson(
                path = path,
                params = mapOf("account" to account, "page" to page.toString()),
                referer = LegacyCampusCardUrls.billReferer(flowId)
            )
            LegacyCampusCardParser.parseEntries(body)
        }

    /**
     * 带一次自动重登的请求：旧平台会话失效时重建会话并重试一次。
     *
     * 重建后仍失效 → 转成 [WbuSessionExpiredException]（业务系统层），由 ViewModel 决定
     * 「静默重登统一认证 → 仍失败才弹登录 Sheet」。
     */
    private suspend fun <T> requestWithSession(block: (String) -> T): T {
        val account = ensureSession()
        return try {
            block(account)
        } catch (e: LegacySessionExpiredException) {
            Log.i(TAG, "legacy session expired, re-establishing")
            val fresh = sessionMutex.withLock {
                sessionAccount = null
                establishSessionOrThrow()
            }
            try {
                block(fresh)
            } catch (e2: LegacySessionExpiredException) {
                throw WbuSessionExpiredException(AccessLayer.Service, e2)
            }
        }
    }

    /**
     * 把旧平台真实地址改写到 WebVPN 代理宿主。
     *
     * 只改写 `yktyd.wbu.edu.cn` 的 81 / 8090 两个端口；已经是代理宿主或其它地址原样返回。
     * 同时改写 `Referer`，避免旧平台把代理请求当成跨站来源。
     */
    private fun rewriteToWebVpn(request: Request): Request {
        val rewrittenUrl = rewriteLegacyUrl(request.url) ?: return request
        val builder = request.newBuilder().url(rewrittenUrl)
        request.header("Referer")?.let { referer ->
            referer.toHttpUrlOrNull()?.let { url ->
                rewriteLegacyUrl(url)?.let { builder.header("Referer", it.toString()) }
            }
        }
        return builder.build()
    }

    private fun rewriteLegacyUrl(url: HttpUrl): HttpUrl? {
        if (!url.host.equals(LEGACY_HOST, ignoreCase = true)) return null
        val proxy = when (url.port) {
            81 -> proxyPort81
            8090 -> proxyPort8090
            else -> return null
        }
        val base = proxy.toHttpUrlOrNull() ?: return null
        return url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
    }

    private fun postJson(path: String, params: Map<String, String>, referer: String): String {
        val formBuilder = FormBody.Builder()
        params.forEach { (key, value) -> formBuilder.add(key, value) }

        val request = Request.Builder()
            .url("${LegacyCampusCardUrls.BASE_URL}/$path")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", referer)
            .post(formBuilder.build())
            .build()

        noRedirectClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (response.code in 300..399) {
                Log.w(TAG, "legacy JSON request redirected (HTTP ${response.code})")
                throw LegacySessionExpiredException()
            }
            if (response.code != 200) {
                throw LegacyCampusCardUnexpectedException("旧一卡通接口返回 HTTP ${response.code}")
            }
            if (LegacyCampusCardUrls.looksLikeLoginPage(response.request.url.toString(), body)) {
                throw LegacySessionExpiredException()
            }
            return body
        }
    }
}

/** 旧平台 URL 与固定表单，单独抽出来便于单元测试钉住。 */
internal object LegacyCampusCardUrls {

    const val BASE_URL = "http://yktyd.wbu.edu.cn:81"
    const val LEGACY_PRELOGIN_ORIGIN = "http://yktyd.wbu.edu.cn:8090/"
    const val LEGACY_INDEX_REFERER = "$BASE_URL/Phone/Index"

    /**
     * 旧平台自己的 CAS 入口。注意 `continueurl` 里的 `:` / `/` 必须保持编码状态，
     * 整体再作为 `service` 参数编码一次（与浏览器实际发出的地址一致）。
     */
    const val CAS_PRELOGIN =
        "http://yktyd.wbu.edu.cn:8090/ias/prelogin?sysid=FWDT&continueurl=http%3A%2F%2Fyktyd.wbu.edu.cn%3A81%2Fcassyno%2Findex"

    /**
     * 旧平台按 UA 分流：移动 UA → `/phone/cassyn`，桌面 UA → `/user/user`。
     * 所有旧平台请求（包括 CAS 换票）都固定用它。
     */
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 16; Pixel 10) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 「我的账单」flow。 */
    const val FLOW_BILL = 150

    /** 「流水查询」flow。 */
    const val FLOW_FLOW = 15

    fun casLoginUrl(idsBase: String = "http://ids.wbu.edu.cn"): String =
        "$idsBase/authserver/login?service=" + URLEncoder.encode(CAS_PRELOGIN, "UTF-8")

    fun pageUrl(): String = "$BASE_URL/Page/Page"

    fun billReferer(flowId: Int): String = "$BASE_URL/PPage/ComePage?flowID=$flowId"

    /** 进入账单页必须先 POST 这个表单；直接 GET `PPage/ComePage` 会返回「页面找不到了」。 */
    fun pageForm(flowId: Int): Map<String, String> = linkedMapOf(
        "flowID" to flowId.toString(),
        "type" to "3",
        "apptype" to "2",
        "Url" to "",
        "comeapp" to "",
        "parm11" to "0",
        "parm22" to "",
        "headnames" to "1",
        "freamenames" to "",
        "shownamess" to "",
        "webheadhide" to "1",
        "sMenuName" to "",
        "sEMenuName" to "",
        "boothcard" to "0"
    )

    /** 登录页 / 会话失效页判据。 */
    fun looksLikeLoginPage(finalUrl: String, body: String): Boolean {
        if (finalUrl.contains("/Phone/Login", ignoreCase = true)) return true
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("<")) return false
        return trimmed.contains("<title>登录</title>") ||
            trimmed.contains("/Phone/Login", ignoreCase = true) ||
            trimmed.contains("txt_yzm")
    }
}

/** 旧平台会话失效（页面被踢回 `/Phone/Login`）；只在本类内部用于触发一次重建。 */
internal class LegacySessionExpiredException : Exception()

/** 旧平台返回了内容，但不符合预期（改版 / 错误页）。 */
class LegacyCampusCardUnexpectedException(message: String) : Exception(message)
