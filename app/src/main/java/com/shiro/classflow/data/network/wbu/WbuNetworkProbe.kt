package com.shiro.classflow.data.network.wbu

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 校园网环境探测。
 *
 * 通过访问校园网认证页（仅校内可达、响应快且压力小）判断是否处于校园网，
 * 避免探测 jwxt（可能卡顿）或依赖 SSID（需权限）/网段（不可靠）。
 *
 * 探测语义：认证页真回了一个正常响应（登录页 200 / 跳转 3xx）即视为校园网；
 * 连不上 / 超时 / 连接被拒 / DNS 失败 / 只拿到网关自己的错误页视为非校园网。
 *
 * 两种探测：
 * - [refresh]：**用户正在等着看结果**的那次探测（登录面板里切直连 / 切 WebVPN、WebView 首屏），
 *   超时放宽（4s）、不缓存，宁可慢一点也不误判；
 * - [fastRefresh]：**站在请求路径上**的探测（「自动校园网探测」给成绩 / 选课 / 同步这类
 *   需要校园网的流程选通道），短超时 + 短缓存 —— 用户点开页面后是看着 Loading 等它的。
 *
 * 两种探测都必须**真的发一个 HTTP 请求**：tun 模式的本机代理 / VPN 会自己接管 TCP，
 * 「连得上」在它那里是假象（实测：代理在跑时连 `10.255.255.1:80` 都是 10ms 就成功），
 * 只有让请求真的走一趟、拿回一个像样的响应，才能证明「人在校园网里」。
 *
 * 另外：**只可能是校外**的链路（纯蜂窝 / 无网）直接判校外 —— 校内认证页是私网地址，
 * 蜂窝链路路由不到它，没必要白等一次超时（带 VPN / 网络共享 / 有线时不算，见
 * [mightBeOnCampusNetwork]）：校外用流量时最长的那次等待就这样变成 0。
 */
object WbuNetworkProbe {

    // 校园网认证页，校内专用、外部不可达；作为「是否处于校园网」的轻量探测目标
    private const val CAMPUS_PORTAL_URL = "http://172.16.90.162/"

    private const val CONNECT_TIMEOUT_MS = 4000L
    private const val READ_TIMEOUT_MS = 4000L
    private const val CALL_TIMEOUT_MS = 6000L

    // 快速探测：一次「尽量短」的 HTTP（校内一个来回就够；校外/被代理捣乱时按这个超时收场）
    private const val FAST_CONNECT_TIMEOUT_MS = 500L
    private const val FAST_READ_TIMEOUT_MS = 500L
    private const val FAST_CALL_TIMEOUT_MS = 700L

    /**
     * 快速探测结果的短缓存时长。
     *
     * 校园网是瞬时状态，缓存不能长；但只要**网络本身没变**（WiFi 与蜂窝切换、换 AP 都会
     * 通过 [watchNetworkChanges] 立刻作废缓存），这十几秒内的结论就没有理由重算一遍 ——
     * 一次「打开成绩页」可能连做十几次需要选通道的请求，逐个探测既没必要也更慢。
     */
    private const val FAST_CACHE_TTL_MS = 60_000L

    // 最近一次探测结果。null 表示尚未探测到。
    private val _campusState = MutableStateFlow<Boolean?>(null)
    val campusState: StateFlow<Boolean?> = _campusState.asStateFlow()

    // 独立短超时 client，不复用 WbuSyncEngine 的 15s client
    // 刻意不传 context：探测打的是裸 IP，不该装学校 DNS（否则 Dns → 探测 → Dns 循环）
    private val probeClient: OkHttpClient by lazy {
        WbuCampusNetwork.install(
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
        ).build()
    }

    private val fastProbeClient: OkHttpClient by lazy {
        WbuCampusNetwork.install(
            OkHttpClient.Builder()
                .connectTimeout(FAST_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(FAST_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(FAST_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
        ).build()
    }

    // 快速探测的短缓存（值 + 写入时刻，单调时钟）
    @Volatile
    private var fastCacheValue: Boolean? = null

    @Volatile
    private var fastCacheAtMs: Long = 0L

    @Volatile
    private var networkWatcherRegistered = false

    /**
     * 实时探测当前是否处于校园网，并更新 [campusState]。
     * 开始时先置 null（UI 显示「检测中」），避免复用上一次结果；
     * 返回探测到的结果（boolean）。
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        _campusState.value = null // 进入检测中
        val reachable = probeHttp(probeClient)
        _campusState.value = reachable
        reachable
    }

    /**
     * 快速探测（短超时 + 短缓存），供「自动校园网探测」在请求路径上使用。
     *
     * 与 [refresh] 的差别：
     * - 在**只可能是校外**的网络上（纯蜂窝 / 无网）直接判定「不在校园网」：校园认证页是私网地址，
     *   蜂窝链路路由不到它，没必要白等一次超时（带 VPN / 网络共享 / 有线时不算，见
     *   [mightBeOnCampusNetwork]）；
     * - 只发一次**短超时 HTTP**（700ms 内必须拿回响应）。不能只连 TCP：本机 tun 代理会假装连上，
     *   于是人在校外也会被误判成「在校园网」、反而去走直连；
     * - 结果缓存 60s，且网络一变立刻作废（[watchNetworkChanges]）；
     * - **不把 [campusState] 置成「检测中」**：它是给后台流程选通道用的，
     *   不该让正在看的登录面板提示闪一下。
     *
     * 结果同样发布到 [campusState]（校园网状态是一致的），让登录面板的提示不至于过期。
     */
    suspend fun fastRefresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        watchNetworkChanges(appContext)

        if (!mightBeOnCampusNetwork(appContext)) return@withContext publishFast(false)

        val now = SystemClock.elapsedRealtime()
        fastCacheValue?.let { cached ->
            if (now - fastCacheAtMs < FAST_CACHE_TTL_MS) return@withContext cached
        }
        publishFast(probeHttp(fastProbeClient))
    }

    /**
     * 忘掉快速探测的短缓存：用户刚改完相关设置（如「自动校园网探测」）或网络刚变过，
     * 下一次访问理应重新判断，而不是继续用旧结果。
     */
    fun invalidateFastCache() {
        fastCacheValue = null
        fastCacheAtMs = 0L
    }

    /**
     * 「需要校园网」的流程（教务 / 图书馆 / 选课 …）在发起访问前探一次校园网。
     *
     * 开了「自动校园网探测」用 [fastRefresh]（快速、带短缓存），
     * 关掉则沿用原来的 [refresh]（只增不减：关掉即回到加这个开关之前的行为）。
     *
     * 需要校园网的流程（教务 / 一卡通 / 图书馆 / 选课 …）调用它。
     */
    suspend fun probeForCampusFlow(context: Context): Boolean =
        if (WbuAuthTransport.isAutoCampusProbeEnabled(context)) fastRefresh(context) else refresh()

    // ------------------- 「能不能到校内」（供学校 DNS 用） -------------------

    /**
     * 独立于选通道探测的缓存。
     *
     * 为什么不共用 [fastRefresh] 的缓存：两者**语义不同**。
     * - [fastRefresh] 回答「人在不在校园网」，用来选直连还是 WebVPN；
     * - [probeCampusReachable] 回答「能不能到校内」，用来决定要不要用学校 DNS。
     *
     * 校园 VPN 用户、宿舍网线共享的用户都能「到校内」，却不在校园 WiFi 里；
     * 反过来，某些代理规则下校园 WiFi 也可能到不了校内。混用一个值迟早会互相打脸。
     */
    @Volatile
    private var reachableCacheValue: Boolean? = null

    @Volatile
    private var reachableCacheAtMs: Long = 0L

    /** 校内可达性缓存时长：与快速探测一致，网络一变立刻作废。 */
    private const val REACHABLE_CACHE_TTL_MS = 60_000L

    /**
     * 能否**到达校内**（不要求「人在校园网」）。
     *
     * 判据与 [fastRefresh] 相同：打一次校内认证页的裸 IP，拿回 2xx/3xx 即视为可达。
     * 用裸 IP 是刻意的 —— 本函数的目的就是绕开 fake-IP，若自己也走域名就自相矛盾。
     *
     * 失败**也缓存**（60s）：校外用户每个请求都等一次探测超时才叫真慢；
     * 而网络一变缓存立刻作废，不会把「刚进校园网」误判成不可达。
     *
     * 注意这与「学校 DNS 查询失败不缓存」是两回事：那次是解析结果本身不可信，
     * 这次只是「可达性」这个开关状态的短时快照。
     */
    suspend fun probeCampusReachable(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        watchNetworkChanges(appContext)

        val now = SystemClock.elapsedRealtime()
        reachableCacheValue?.let { cached ->
            if (now - reachableCacheAtMs < REACHABLE_CACHE_TTL_MS) return@withContext cached
        }

        // 纯蜂窝基本到不了校内私网地址；但带 VPN 时不算（与 fastRefresh 同一判据）
        if (!mightBeOnCampusNetwork(appContext)) return@withContext publishReachable(false)

        publishReachable(probeHttp(fastProbeClient))
    }

    /**
     * 同 [probeCampusReachable]，但**阻塞**调用。
     *
     * 存在的唯一原因：OkHttp 的 [okhttp3.Dns.lookup] 是同步 API，而 Dns 回调本来就在
     * 工作线程上（不会卡 UI）。用它就不必要求「先有人调过异步探测」——
     * 否则冷启动后的第一次域名解析会因为缓存为空而白白退回系统 DNS。
     */
    fun probeCampusReachableBlocking(context: Context): Boolean {
        val appContext = context.applicationContext
        watchNetworkChanges(appContext)

        val now = SystemClock.elapsedRealtime()
        reachableCacheValue?.let { cached ->
            if (now - reachableCacheAtMs < REACHABLE_CACHE_TTL_MS) return cached
        }

        if (!mightBeOnCampusNetwork(appContext)) return publishReachable(false)

        return publishReachable(probeHttp(fastProbeClient))
    }

    /** 校内可达性的**同步**快照（不触发探测），仅用于日志 / 其它只读场景。 */
    fun campusReachableSnapshot(): Boolean = reachableCacheValue == true

    /** 供测试 / 设置变更时清空。 */
    fun invalidateReachableCache() {
        reachableCacheValue = null
        reachableCacheAtMs = 0L
    }

    private fun publishReachable(value: Boolean): Boolean {
        reachableCacheValue = value
        reachableCacheAtMs = SystemClock.elapsedRealtime()
        return value
    }

    private fun publishFast(value: Boolean): Boolean {
        fastCacheValue = value
        fastCacheAtMs = SystemClock.elapsedRealtime()
        _campusState.value = value
        return value
    }

    /**
     * 网络一变（WiFi ↔ 蜂窝、换 AP / 换 WiFi）就把快速探测的缓存作废。
     * 注册失败（权限被限制等）不算错误：退化成只用 60s 的短缓存。
     */
    private fun watchNetworkChanges(context: Context) {
        if (networkWatcherRegistered) return
        synchronized(this) {
            if (networkWatcherRegistered) return
            networkWatcherRegistered = true
            runCatching {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return
                manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = invalidateAllCaches()

                    override fun onLost(network: Network) = invalidateAllCaches()

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) = invalidateAllCaches()
                })
            }
        }
    }

    /** 网络一变，**两份**缓存都作废（选通道的 + 校内可达性的）。 */
    private fun invalidateAllCaches() {
        invalidateFastCache()
        invalidateReachableCache()
    }

    /**
     * 当前这条网络**可能是校园网**吗？
     *
     * 只有「纯蜂窝」才敢直接判定「不是校园网」：校内认证页（`172.16.90.162`）是私网地址，
     * 蜂窝链路根本路由不到它。**注意这只是「能不能走校园网直连」，不是「能不能用校园服务」** ——
     * 判定为「不是校园网」只会让流程改走 WebVPN（本来就是给校外用的那条通道），不会变成不能用。
     *
     * 其它传输方式一律**真去探一次**，因为它们都可能碰到校园网：
     * - WiFi：校园网的主入口；
     * - 以太网 / USB / 蓝牙网络共享：把自己的手机接到「已经连上校园网的设备」上（宿舍/实验室很常见）；
     * - VPN：蜂窝 + 学校 SSL VPN（如 EasyConnect）同样能到校内，这时直连也是可用的。
     *
     * 拿不到网络信息时**保守返回 true**：宁可多探一次（多等 0.7s），
     * 也不要把身在校园网的用户误判成校外、白绕一圈 WebVPN。
     */
    private fun mightBeOnCampusNetwork(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
        capabilities != null && (
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
            )
    }.getOrDefault(true)

    /**
     * 认证页回一个正常响应（登录页 200 / 跳转 3xx）即视为在校内。
     *
     * 只认 2xx/3xx：代理 / 网关自己的 4xx、5xx 错误页不是校园网的证据（把它当成「在校园网」
     * 会让流程白白跑去直连，然后失败）。判据本身由 [isCampusPortalResponseCode] 决定并被单测钉住。
     */
    private fun probeHttp(client: OkHttpClient): Boolean = runCatching {
        client.newCall(Request.Builder().url(CAMPUS_PORTAL_URL).get().build())
            .execute()
            .use { response -> isCampusPortalResponseCode(response.code) }
    }.getOrDefault(false)
}

/**
 * 认证页的响应码算不算「人在校园网内」的证据。
 *
 * 宁可判错也要往「不在校园网」的方向错：判成校外只会让流程改走 WebVPN（本来就是给校外用的那条路），
 * 判成校内却会让人在校外时去直连一个根本到不了的地址、最后硬失败。
 */
internal fun isCampusPortalResponseCode(code: Int): Boolean = code in 200..399
