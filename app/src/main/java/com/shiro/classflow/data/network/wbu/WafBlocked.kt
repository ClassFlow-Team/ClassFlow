package com.shiro.classflow.data.network.wbu

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 校园站点前面的云 WAF 把请求拦下来了。
 *
 * 实测形态（同一朵 WAF，多节点）：正常请求 `200` / `302`，命中攻击特征时
 * `403` + 7.2 KB 的 websaas 自定义页（`error.websaas.cn` / `XWD-Token`）；抓包里那次被回成
 * `404` + 4.5 KB 的同一张页（用 404 冒充「页面不存在」）。
 *
 * **走没走 WAF 由 DNS 决定，不由 IP 白名单决定**（2026-11-02 实测）：
 * 校内递归把 `ids.wbu.edu.cn` 解析到内网 `172.18.0.123`，请求不经过云 WAF；
 * 公网 / 蜂窝解析到 `a005v6.cache.dbappwaf.cn` 才过 WAF。所以「校园网内能用、校外不能用」
 * 是路由差异，不是风控对校园 IP 放行。
 *
 * 源站自己的错误页是**原样透传**的（nginx 404 是 162 B、CAS 的 JSON 错误页带 `route` Cookie
 * 与 `nosniff`），且它们的**响应头与挡板页完全一样** —— 判据只能看正文，不能看头。
 *
 * 仍未查清：HAR 里那次 `authorize` 的 404 挡板页。同一条 URL 从两个出口、任意 UA、任意 Cookie、
 * 10 并发、15 连打，全都回 302；校园 IP 直接打 WAF 节点也一样。别把这类失败当成稳定可复现缺陷。
 */
class WafBlockedException(
    /** 被拦的那个 host，例如 `ids.wbu.edu.cn`。 */
    val host: String?,
    /** WAF 回的 HTTP 状态码（校方这套用 404 冒充）。 */
    val statusCode: Int
) : IOException("blocked by waf: host=$host, status=$statusCode")

/**
 * 源站自己的响应标记：`Set-Cookie`（CAS / 负载均衡的 `route=…` 会话粘滞）与
 * `X-Content-Type-Options`（CAS 的 `nosniff`）。
 *
 * 只用来**快速放过**：带了其中一个，说明响应出自源站或它的应用前置，不必再 peek 正文。
 * **反向不成立** —— 见 [WafBlockDetector] 里那条「没有这两个头 ≠ 被 WAF 拦」的实测反例。
 */
internal fun looksLikeOriginResponse(setCookie: String?, contentTypeOptions: String?): Boolean =
    !setCookie.isNullOrBlank() || !contentTypeOptions.isNullOrBlank()

/**
 * WAF 拦截页的识别规则。
 *
 * **只认正文指纹，不认头结构。** 2026-11-02 复核推翻了「看头结构就能认」的想法：
 * 源站自己的 nginx 404（`/no-such-app/xyz`）与挡板页的头形态**完全一样** ——
 * 都没有 `Set-Cookie`、没有 `X-Content-Type-Options`、都是 `text/html` 不带 charset，
 * 区别只在正文（162 B 的 nginx 页 vs 4.5 KB 的 websaas 页）。按头结构判会把普通 404
 * 说成「被拦截」，用户会被告知去开 WebVPN，而真正的问题是路径不存在。
 *
 * 抽成纯函数是为了能单测 —— 这条判据一旦放宽，普通 4xx 会被误报成「被拦截」；
 * 一旦收紧，用户又会看到「页面不存在」这种假原因。
 */
internal object WafBlockDetector {

    /**
     * 页面特征。前两条是学校当前在用的这家（`websaas.cn` 云防护）自己的指纹，
     * 其余是通用拦截页文案，用来覆盖换厂商 / 换模板的情形。
     *
     * 最后一条是**境内**被拦时的形态（实测）：`503` + 190 B 的
     * `<center>httpd</center>` 默认页 —— 和境外的 403 websaas 页不是同一张，但都是 WAF 回的。
     */
    private val SIGNATURES = listOf(
        "websaas.cn",
        "xwd-token",
        "您的访问被拦截",
        "访问被拦截",
        "请求被拦截",
        "该请求已被拦截",
        "web应用防火墙",
        "web application firewall",
        "access denied",
        // 英文模板的语序有好几种（request blocked / request was blocked / was blocked…），
        // 逐条列出而不是只留一条，免得换个厂商就漏判；最后一条兜住带主语的写法
        "request blocked",
        "request was blocked",
        "request has been blocked",
        "request is blocked",
        "request rejected",
        "was blocked"
    )

    /** 只认错误码：正常页面（200）的脚本里也可能出现这些词，拿它判会误伤。 */
    private const val MIN_ERROR_CODE = 400

    /**
     * @param code        HTTP 状态码
     * @param contentType 响应的 `Content-Type`
     * @param body        响应体（只需前若干 KB）
     */
    fun looksLikeWafBlock(code: Int, contentType: String?, body: String?): Boolean {
        if (code < MIN_ERROR_CODE) return false
        if (body.isNullOrBlank()) return false
        // 拦截页一定是 HTML；JSON / 纯文本的错误体交给各自的业务判据
        if (contentType != null && !contentType.contains("html", ignoreCase = true)) return false

        val lower = body.lowercase()
        return SIGNATURES.any { lower.contains(it) }
    }
}

/**
 * 把 WAF 拦截页从「一个看起来正常的 4xx 响应」变成一眼能认出来的异常。
 *
 * 挂在传输层，所以所有走 OkHttp 的校园请求（ids / 教务 / 图书馆 / 一卡通 …）都自动覆盖，
 * 不必在几十处读响应体的地方各写一遍。用 [Response.peekBody] 只看不消费，
 * 即使不是拦截页也不影响调用方读取响应体。
 *
 * 顺序：
 * 1. 不是错误码、或不是 HTML → 直接放过；
 * 2. 带源站标记（`Set-Cookie` / `X-Content-Type-Options`，见 [looksLikeOriginResponse]）→ 直接放过，
 *    连正文都不读：源站的 HTML 错误页是原样透传的（实测 nginx 404 与 CAS 错误页都带这两个头之一）；
 * 3. 其余才 peek 正文、按 [WafBlockDetector] 的正文指纹判。
 *
 * 为什么不再按头结构判（2026-11-02 实测推翻）：源站 nginx 的 404 与挡板页的头**完全一样**
 * （无 Cookie、无 nosniff、`text/html` 无 charset），只有正文能分开。按头判会把「路径不存在」
 * 说成「被 WAF 拦」，让用户白开一次 WebVPN。
 */
internal class WafBlockInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code < 400) return response

        val contentType = response.header("Content-Type")
        if (contentType != null && !contentType.contains("html", ignoreCase = true)) return response

        // 源站自己发的错误页：源站的 4xx 一律原样透传，不必再读正文
        if (looksLikeOriginResponse(response.header("Set-Cookie"), response.header("X-Content-Type-Options"))) {
            return response
        }

        val body = runCatching { response.peekBody(MAX_PEEK_BYTES).string() }.getOrNull()
        if (!WafBlockDetector.looksLikeWafBlock(response.code, contentType, body)) return response

        response.close()
        throw WafBlockedException(chain.request().url.host, response.code)
    }

    private companion object {
        /** 拦截页实测 4.5 KB（境外）/ 190 B（境内），取 16KB 足够覆盖模板变化。 */
        const val MAX_PEEK_BYTES = 16L * 1024
    }
}
