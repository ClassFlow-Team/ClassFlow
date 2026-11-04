package com.shiro.classflow.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 「这台手机现在到底有没有网」的唯一判据。
 *
 * ## 为什么需要它
 *
 * 没有网的时候，请求要等满超时才失败（校园网探测 4s、业务请求 15s），最后显示的还是一句
 * 「网络连接失败，请检查网络后重试」—— 用户看不出是「手机没联网」还是「学校的服务器连不上」，
 * 而这两件事该点的按钮完全不同。所以要在**发起请求的那一刻**就判断出来，并且给它一句准确的话。
 *
 * ## 判据：只看「系统有没有默认网络」，其它一律不算
 *
 * 只有一种情况敢说「没有网络」：
 *
 * ```
 * ConnectivityManager.activeNetwork == null
 * ```
 *
 * 即系统自己说「当前没有任何默认网络」。这条判据最保守，也正好挡住所有会误判的情形：
 *
 * | 场景 | 系统里是什么 | 本判据 | 为什么不能更严 |
 * | --- | --- | --- | --- |
 * | 有线网络（USB-C 网卡 / 坞站） | `TRANSPORT_ETHERNET` | 有网 | 宿舍、实验室最常见的联网方式 |
 * | Gnirehtet / 反向共享（USB 网络共享） | `TRANSPORT_VPN`，且往往**没有** `NET_CAPABILITY_VALIDATED` | 有网 | 它的「校验不过」是宿主机替它上网的结果，真判成没网就废了这条链路 |
 * | 校园网 Wi-Fi（要先过认证页） | 有 `TRANSPORT_WIFI`，但 `NET_CAPABILITY_INTERNET` / `VALIDATED` 常常为 false | 有网 | 校园网本来就是「连上了但上不了公网」，判成没网会让 `172.16.90.162` 的探测彻底没法做 |
 * | 只连了局域网 / 内网 | 同上 | 有网 | 校内服务本来就走内网 |
 * | 蓝牙网络共享 | `TRANSPORT_BLUETOOTH` | 有网 | 同样是合法的联网方式 |
 * | 飞行模式 / 关了 Wi-Fi 和移动数据 | `activeNetwork == null` | **没网** | 用户确实该看到「未连接网络」 |
 *
 * 两条**刻意不用**的判据，写在这里防止以后被人「优化」回去：
 *
 * 1. **不看 `NET_CAPABILITY_VALIDATED`**：它就是「能不能真的访问公网」的意思。Gnirehtet、
 *    校园网认证前的 Wi-Fi、只连内网的实验室机器全部为 false，但它们都能用 —— 拿它当「没网」
 *    会造成用户最烦的那种误报。
 * 2. **不看 `NET_CAPABILITY_INTERNET`**：同上，校园网 / 内网环境下它也可能是 false。
 *
 * 拿不到 `ConnectivityManager` 或读 `activeNetwork` 抛异常时，一律 **fail-open（当作有网）**：
 * 多等一次超时的代价，远小于在用户明明有网时弹一句「未连接网络」。
 */
object NetworkAvailability {

    enum class State {
        /** 系统有默认网络（不论它是 WiFi / 有线 / 蜂窝 / VPN / USB / 蓝牙共享）。 */
        Online,

        /** 系统连一个默认网络都没有。 */
        Offline
    }

    /**
     * 发起请求时手机没有网络的通知。
     *
     * 由 [NoNetworkInterceptor] 在拦下请求时发出，UI 层（`AppNavigation`）收下来弹一条提示。
     * 只是「提示」用的旁路，判据本身不依赖有没有人订阅。
     */
    private val _offlineRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val offlineRequests: SharedFlow<Unit> = _offlineRequests.asSharedFlow()

    /** 两次提示之间的最小间隔：一次操作里往往并发好几个请求，不该连弹好几条。 */
    private const val REPORT_MIN_INTERVAL_MS = 3_000L

    @Volatile
    private var lastReportAtMs = 0L

    /**
     * 记一次「没网还想发请求」。返回 true 表示这次确实发出了提示（未被间隔挡掉）。
     */
    fun reportOfflineRequest(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReportAtMs < REPORT_MIN_INTERVAL_MS) return false
        lastReportAtMs = now
        return _offlineRequests.tryEmit(Unit)
    }

    /** 当前网络状态。 */
    fun state(context: Context): State = decideState(snapshot(context))

    /** 是否**确定**没有网络。任何读不到信息的情形都返回 false（fail-open）。 */
    fun isOffline(context: Context): Boolean = state(context) == State.Offline

    /**
     * 抓一眼系统当下的网络。返回 null 表示读不到（没有 ConnectivityManager 等），
     * 由 [decideState] 当作「有网」处理。
     */
    internal fun snapshot(context: Context): LinkSnapshot? = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching null

        val active = manager.activeNetwork ?: return@runCatching LinkSnapshot(
            hasActiveNetwork = false,
            hasCapabilities = false,
            transports = emptySet()
        )

        val capabilities = manager.getNetworkCapabilities(active) ?: return@runCatching LinkSnapshot(
            hasActiveNetwork = true,
            hasCapabilities = false,
            transports = emptySet()
        )

        LinkSnapshot(
            hasActiveNetwork = true,
            hasCapabilities = true,
            transports = buildSet {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkTransport.Wifi)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetworkTransport.Cellular)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetworkTransport.Ethernet)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetworkTransport.Vpn)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB)) add(NetworkTransport.Usb)
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add(NetworkTransport.Bluetooth)
            }
        )
    }.getOrNull()
}

/** 系统报出来的网络传输方式。只用于判断与日志，不参与「有没有网」的取舍（见 [NetworkAvailability]）。 */
internal enum class NetworkTransport { Wifi, Cellular, Ethernet, Vpn, Usb, Bluetooth }

/** 一次网络快照。字段全部来自系统，不含任何猜测。 */
internal data class LinkSnapshot(
    /** `ConnectivityManager.activeNetwork != null`。 */
    val hasActiveNetwork: Boolean,
    /** 拿不拿得到该网络的 `NetworkCapabilities`（切换瞬间可能拿不到）。 */
    val hasCapabilities: Boolean,
    val transports: Set<NetworkTransport>
)

/**
 * 快照 → 状态。抽成纯函数是为了让单测能钉住「有线 / Gnirehtet 不能被判成没网」这条线：
 * 以后谁把判据改成「只认 WiFi 和蜂窝」，这里会立刻红。
 */
internal fun decideState(snapshot: LinkSnapshot?): NetworkAvailability.State {
    // 读不到系统网络信息（没有 ConnectivityManager / 调用失败）：绝不说「没有网络」
    if (snapshot == null) return NetworkAvailability.State.Online
    // 系统连一个默认网络都没有 —— 这是唯一敢断言「没网」的证据
    if (!snapshot.hasActiveNetwork) return NetworkAvailability.State.Offline
    // 有默认网络就是有网：不看 VALIDATED / INTERNET 能力位，也不看具体是哪种传输方式
    return NetworkAvailability.State.Online
}

/**
 * 手机根本没有默认网络时还要发请求 → 抛这个，让上层能把它渲染成「未连接网络」
 * 而不是笼统的「网络连接失败」。
 */
class NoNetworkException(val url: String? = null) : IOException(
    if (url.isNullOrBlank()) "no default network" else "no default network: $url"
)

/**
 * 请求闸门：没网时**立刻**失败，别让用户干等一次超时，同时通知 UI 弹提示。
 *
 * 只拦「确定没网」的情况（见 [NetworkAvailability]），所以不会误伤有线 / Gnirehtet / 校园网。
 * 回环地址（`localhost` 这类本机服务）永远放行。
 */
internal class NoNetworkInterceptor(context: Context) : Interceptor {

    private val appContext: Context = context.applicationContext

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"

        if (!isLoopback && NetworkAvailability.isOffline(appContext)) {
            NetworkAvailability.reportOfflineRequest()
            throw NoNetworkException(request.url.toString())
        }
        return chain.proceed(request)
    }
}
