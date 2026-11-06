package com.shiro.classflow.data.network.wbu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 洗衣订单查询的会话入口（页面、Worker、通知动作共用）。
 *
 * 为什么单独抽一层：
 * - 订单接口全在 U净 域，必须先拿一卡通平台票再换 U净 token，这一套有 4 跳网络；
 * - 页面刷新、后台轮询、通知点开都要用，散着写就会出现三份「换票 + 重试」；
 * - U净 token 是 **JWT**，自带 `exp`，可以直接在本地判断是否过期，不必先打一次接口试错。
 */
object WasherOrderSession {

    private const val TAG = "WasherOrderSession"

    /** U净 token 的本地缓存：换一次能撑很久（实测 90 天），没必要每次都换。 */
    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenExpiresAtSeconds: Long = 0L

    /** 提前 5 分钟视为过期，避免「刚好卡在边界上」的那一次请求。 */
    private const val EXPIRY_SKEW_SECONDS = 300L

    /** 最近一次失败原因，供页面显示（不落库，进程内即可）。 */
    @Volatile
    var lastFailure: Throwable? = null
        private set

    fun clear() {
        token = null
        tokenExpiresAtSeconds = 0L
        lastFailure = null
    }

    /**
     * 建一个**已登录**的 U净 client。
     *
     * @param forceRefresh 忽略缓存、强制重新换票（401/403 后重试一次用）
     */
    suspend fun client(context: Context, forceRefresh: Boolean = false): WbuUjingClient =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val cardClient = WbuCampusCardClient(appContext, useVpn = false)
            val ujing = WbuUjingClient(appContext, cardClient)

            if (!forceRefresh && isCachedTokenValid()) {
                val cached = token
                if (cached != null) {
                    ujing.adoptSession(cached)
                    return@withContext ujing
                }
            }

            // 平台票交给 ensureValidAccessToken 自己判断：它内部会校验会话、优先用
            // refresh_token 续期（不顶号），只有真的失效才重走 CAS。不要在这里自己缓存，
            // 否则后台轮询会拿着一份早就过期的票反复撞墙。
            val platform = cardClient.ensureValidAccessToken()
            val user = ujing.connect(platform, appId = WbuUjingClient.WASHER_APP_ID)
            val fresh = ujing.ujingToken
            if (fresh.isNullOrBlank()) throw java.io.IOException("U净 未返回 token（${user.id}）")
            token = fresh
            tokenExpiresAtSeconds = jwtExpiresAtSeconds(fresh) ?: 0L
            lastFailure = null
            ujing
        }

    /**
     * 带一次自动重登的订单查询。
     *
     * 后台轮询最怕「token 静默过期 → 一直查不到 → 永远不提醒」：
     * 这里把 401/403 当成一次明确信号，换票后重试一次；再失败才把异常抛给调用方。
     */
    suspend fun <T> withClient(
        context: Context,
        block: suspend (WbuUjingClient) -> T,
    ): T = withContext(Dispatchers.IO) {
        runCatching { block(client(context)) }.getOrElse { first ->
            if (!isAuthFailure(first)) {
                lastFailure = first
                throw first
            }
            Log.i(TAG, "U净 登录态失效，重新换票后重试一次", first)
            runCatching { block(client(context, forceRefresh = true)) }
                .onFailure { lastFailure = it }
                .getOrThrow()
        }
    }

    /** 401/403 与业务码异常都算「需要重新换票」。 */
    private fun isAuthFailure(t: Throwable): Boolean =
        (t as? WbuUjingClient.UjingApiException)?.code in setOf(401, 403) ||
            // 平台票失效时换票链路自己会抛这个（没有 CASTGC / refresh_token 都过期）
            t is WbuSessionExpiredException

    private fun isCachedTokenValid(): Boolean {
        val cached = token ?: return false
        if (cached.isBlank()) return false
        val exp = tokenExpiresAtSeconds
        if (exp <= 0L) return true // 解不出 exp 就当有效，真过期由 401 重试兜底
        val now = System.currentTimeMillis() / 1000L
        return now + EXPIRY_SKEW_SECONDS < exp
    }

    /**
     * 从 JWT 的 payload 里取 `exp`（秒）。
     *
     * U净 token 形如 `header.payload.signature`，payload 是 base64url 的 JSON。
     * 解不出来返回 null —— 调用方会退化成「先试一次、401 再换票」。
     */
    fun jwtExpiresAtSeconds(jwt: String): Long? = runCatching {
        val payload = jwt.split('.').getOrNull(1) ?: return null
        val padded = payload.replace('-', '+').replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        val decoded = String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT))
        Regex("\"exp\"\\s*:\\s*(\\d+)").find(decoded)?.groupValues?.get(1)?.toLongOrNull()
    }.getOrNull()
}
