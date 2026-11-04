package com.shiro.classflow.ui.webapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 网页定位桥：把安卓这边的真实位置交给自己容器里的网页。
 *
 * ## 为什么需要它
 *
 * 门户里的业务系统（签到助手 / 查寝 / 打卡 / 场馆预约 …）几乎都是 http 站点：
 * `ehall.wbu.edu.cn` 只开了 80 端口，https 连握手都过不去（实测直接连接失败）。
 * 而 Chromium 对「非安全源」的 `navigator.geolocation` 是**硬拒绝**的：
 *
 * ```
 * code 1: Only secure origins are allowed (see: https://goo.gl/Y0ZkNV).
 * ```
 *
 * 这一步发生在权限判定**之前**，所以哪怕应用申请并授予了定位权限、WebView 打开
 * `setGeolocationEnabled(true)`，页面照样拿不到真位置（连「向系统要权限」都轮不上）。
 *
 * 结果是页面上那套定位逻辑只能走地图 SDK 自己的兜底：百度 JS SDK 会退回 IP 级定位，
 * 实测偏了 8.5 公里（学校在东风大道 816 号，IP 定位落到蔡甸街道知音湖大道），而且它是
 * **死的** —— 不随人走、精度字段为空。签到判定「在不在签到范围内」于是永远是否。
 *
 * ## 怎么修
 *
 * 不去跟 Blink 较劲，直接**把网页用的那个 API 换掉**：
 *
 * 1. 容器在 WebView 上注册 [WebAppGeolocationScript.BRIDGE_NAME]（本文件的
 *    [WebAppGeolocationController]）；
 * 2. [WebAppGeolocationScript.JS] 在页面里接管 `navigator.geolocation` 的
 *    `getCurrentPosition` / `watchPosition` / `clearWatch`，转调原生桥；
 * 3. 原生桥按需申请定位权限，用 [LocationManager] 取一次定位，把结果回灌给页面。
 *
 * 位置以 **WGS84** 原样交给页面，不在这里换算 —— 页面侧的地图 SDK 本来就负责那件事，
 * 实测百度 JS SDK 拿到 WGS84 后换回 BD09 的误差 < 1 米（喂武汉商学院的 WGS84，
 * 页面算出来的正是 `114.093859 / 30.465926`）。
 *
 * ## 边界
 *
 * 只接管定位三件套，不动 `navigator.permissions`：实测百度 JS SDK 只调定位、不查权限
 * （打桩后链路全程正常）。以后真遇到依赖 Permissions API 的页面再补。
 *
 * 也正因为整只 `navigator.geolocation` 都被换掉了，Blink 自己的定位入口在这条路上根本不会被走到：
 * `WebSettings.setGeolocationEnabled` 与 `WebChromeClient.onGeolocationPermissionsShowPrompt`
 * 都不需要实现（后者默认既不授权也不回调，页面只会永久挂住 —— 所以更不能指望它）。
 */
internal object WebAppGeolocationScript {

    /** 原生桥在页面里的名字（JS 与 Kotlin 两边共用同一个常量，测试里会校验）。 */
    const val BRIDGE_NAME = "CFGeoBridge"

    /**
     * 注入页面、接管 `navigator.geolocation` 的脚本（幂等）。
     *
     * 细节：
     * - 桥不在（别的 WebView、旧页面）就直接退出，绝不假装自己能定位；
     * - 回调对象按 W3C 的形状给：成功给 `{coords:{latitude, longitude, accuracy, …}, timestamp}`，
     *   失败给 `{code, message, PERMISSION_DENIED, POSITION_UNAVAILABLE, TIMEOUT}` —— 很多地图
     *   SDK 会读 `code` 或那几个常量来决定是否降级到 IP 定位；
     * - 带 25 秒兜底超时：容器跨页重挂时挂起的回调不能把页面永久卡住（签到页在定位失败时
     *   连 loading 遮罩都不会关，卡住的表现就是转圈到天荒地老）；
     * - `watchPosition` 用「交付一次 → 隔 5 秒再问一次」实现，`clearWatch` 撤销；
     * - 成功后把最后一次原始回执留在 `window.__cfGeoLastResult`，方便真机排障。
     */
    val JS: String = """
        (function () {
            if (window.__cfGeoShimInstalled) { return; }
            var bridge = window.$BRIDGE_NAME;
            if (!bridge || !bridge.request) { return; }
            window.__cfGeoShimInstalled = true;

            var pending = {};
            var seq = 0;
            var askTimeoutMs = 25000;
            var watchIntervalMs = 5000;

            function makePosition(payload) {
                return {
                    coords: {
                        latitude: payload.latitude,
                        longitude: payload.longitude,
                        accuracy: payload.accuracy,
                        altitude: null,
                        altitudeAccuracy: null,
                        heading: null,
                        speed: null
                    },
                    timestamp: payload.time || (new Date()).getTime()
                };
            }

            function makeError(code, message) {
                return {
                    code: code,
                    message: message,
                    PERMISSION_DENIED: 1,
                    POSITION_UNAVAILABLE: 2,
                    TIMEOUT: 3
                };
            }

            function clearTimer(entry) {
                if (entry && entry.timer) { clearTimeout(entry.timer); entry.timer = 0; }
            }

            function ask(id, entry) {
                entry.timer = setTimeout(function () {
                    var current = pending[id];
                    if (!current) { return; }
                    if (current.watch) { ask(id, current); return; }
                    delete pending[id];
                    if (current.error) { current.error(makeError(3, '定位超时')); }
                }, askTimeoutMs);
                try {
                    bridge.request(id);
                } catch (e) {
                    clearTimer(entry);
                    delete pending[id];
                    if (entry.error) { entry.error(makeError(2, '定位桥调用失败')); }
                }
            }

            window.__cfGeoDeliver = function (id, payload) {
                window.__cfGeoLastResult = payload;
                var entry = pending[id];
                if (!entry) { return; }
                clearTimer(entry);
                if (payload && payload.ok) {
                    if (entry.success) { entry.success(makePosition(payload)); }
                    if (entry.watch) {
                        entry.timer = setTimeout(function () { ask(id, entry); }, watchIntervalMs);
                        return;
                    }
                    delete pending[id];
                    return;
                }
                if (entry.watch) { ask(id, entry); return; }
                delete pending[id];
                if (entry.error) {
                    entry.error(makeError((payload && payload.code) || 2,
                        (payload && payload.message) || '定位失败'));
                }
            };

            var shim = {
                getCurrentPosition: function (success, error, options) {
                    var id = 'cfgeo-' + (++seq);
                    pending[id] = { success: success, error: error, watch: false, timer: 0 };
                    ask(id, pending[id]);
                },
                watchPosition: function (success, error, options) {
                    var id = 'cfgeo-' + (++seq);
                    pending[id] = { success: success, error: error, watch: true, timer: 0 };
                    ask(id, pending[id]);
                    return id;
                },
                clearWatch: function (id) {
                    var entry = pending[id];
                    if (!entry) { return; }
                    clearTimer(entry);
                    delete pending[id];
                    try { bridge.cancel(id); } catch (e) {}
                }
            };

            // 首选整只换掉 navigator.geolocation；换不动就退而改它身上的三个方法
            try {
                Object.defineProperty(navigator, 'geolocation', {
                    configurable: true,
                    enumerable: true,
                    get: function () { return shim; }
                });
            } catch (e) {
                try {
                    navigator.geolocation.getCurrentPosition = shim.getCurrentPosition;
                    navigator.geolocation.watchPosition = shim.watchPosition;
                    navigator.geolocation.clearWatch = shim.clearWatch;
                } catch (e2) {}
            }
            window.__cfGeoShim = shim;
        })();
    """.trimIndent()
}

/**
 * 一次定位结果。
 *
 * [toBridgeJson] 手写 JSON 而不是用 `org.json`：这段字符串要能在纯 JVM 单元测试里断言，
 * 而单元测试里的 `org.json` 是空壳（会抛 "not mocked"）。数值一律按 [Locale.US] 格式化，
 * 否则某些区域设置会把小数点写成逗号，页面 `parseFloat` 直接拿到 `NaN`。
 */
internal data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timeMillis: Long,
    val provider: String?
) {
    fun toBridgeJson(): String = buildString {
        append("{\"ok\":true,\"latitude\":")
        append(String.format(Locale.US, "%.8f", latitude))
        append(",\"longitude\":")
        append(String.format(Locale.US, "%.8f", longitude))
        append(",\"accuracy\":")
        append(String.format(Locale.US, "%.2f", accuracy))
        append(",\"time\":")
        append(timeMillis)
        append(",\"provider\":\"")
        append(escapeJson(provider ?: ""))
        append("\"}")
    }

    companion object {
        fun from(location: Location): GeoFix = GeoFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            timeMillis = location.time,
            provider = location.provider
        )

        /** 失败回执：`code` 用 W3C 那套（1 拒绝 / 2 拿不到 / 3 超时），页面据此决定怎么降级。 */
        fun failureJson(code: Int, message: String): String =
            "{\"ok\":false,\"code\":$code,\"message\":\"" + escapeJson(message) + "\"}"
    }
}

/** JSON 字符串里必须转义的几个字符（provider / 错误文案都是我们自己给的，做最小必要处理）。 */
private fun escapeJson(raw: String): String = buildString {
    raw.forEach { ch ->
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append(" ")
            '\r' -> append(" ")
            else -> append(ch)
        }
    }
}

/**
 * 取一次定位：先看上次已知位置够不够用，否则要一次新的，谁先来算谁，超时退回上次已知位置。
 *
 * 速度优先（发起请求后**第一颗**位置就交付）：签到那类页面的半径通常是几百米，网络定位
 * 在城市里有 WiFi 库加持精度一般在百米级，够用；死等 GPS 冷启动会让用户盯着「获取定位中…」
 * 十几秒。精度字段原样交给页面，页面想怎么判就怎么判。
 */
@SuppressLint("MissingPermission") // 调用方 [WebAppGeolocationController] 必须先确认权限
internal class WebAppLocationReader(
    private val context: Context,
    private val handler: Handler
) {

    /** 上次已知位置的可用时长：太旧的位置不如不要（人早走了）。 */
    private val lastKnownMaxAgeMs = 5 * 60 * 1000L

    /** 上次已知位置的精度上限，超过就宁可重新测。 */
    private val lastKnownMaxAccuracyM = 300f

    /** 当前这一轮挂在系统上的监听与兜底超时（同一时刻只允许一轮）。 */
    private var activeListener: LocationListener? = null
    private var activeTimeout: Runnable? = null

    fun read(onFix: (GeoFix?) -> Unit) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            onFix(null)
            return
        }
        val candidates = providerCandidates(manager)
        val lastKnown = bestLastKnown(manager)

        if (lastKnown != null && isFreshEnough(lastKnown)) {
            Log.i(TAG, "定位：直接用上次已知位置 provider=${lastKnown.provider} accuracy=${lastKnown.accuracy}")
            onFix(GeoFix.from(lastKnown))
            return
        }
        if (candidates.isEmpty()) {
            Log.w(TAG, "定位：没有可用 provider，退回上次已知位置=$lastKnown")
            onFix(lastKnown?.let { GeoFix.from(it) })
            return
        }

        // 两个 provider 同时要，可能先后都回调；回执只允许出去一次
        var delivered = false
        fun deliverOnce(fix: GeoFix?) {
            if (delivered) return
            delivered = true
            onFix(fix)
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                stop(manager)
                Log.i(
                    TAG,
                    "定位：拿到新位置 provider=${location.provider} accuracy=${location.accuracy} " +
                        "lat=${location.latitude} lng=${location.longitude}"
                )
                deliverOnce(GeoFix.from(location))
            }

            @Deprecated("Android 30 起废弃，但 26~29 仍是接口的一部分")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        val timeout = Runnable {
            stop(manager)
            Log.w(TAG, "定位：${LOCATION_TIMEOUT_MS}ms 内没有新位置，退回上次已知位置=$lastKnown")
            deliverOnce(lastKnown?.let { GeoFix.from(it) })
        }
        activeListener = listener
        activeTimeout = timeout

        var registered = false
        candidates.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, handler.looper)
                registered = true
            }.onFailure { Log.w(TAG, "定位：provider $provider 注册失败", it) }
        }
        if (!registered) {
            stop(manager)
            Log.w(TAG, "定位：所有 provider 都注册失败，退回上次已知位置=$lastKnown")
            deliverOnce(lastKnown?.let { GeoFix.from(it) })
            return
        }
        handler.postDelayed(timeout, LOCATION_TIMEOUT_MS)
    }

    /** 收摊：摘监听、撤超时；重复调用无副作用（谁先到谁负责关灯）。 */
    private fun stop(manager: LocationManager) {
        val listener = activeListener
        activeListener = null
        if (listener != null) {
            runCatching { manager.removeUpdates(listener) }
        }
        val timeout = activeTimeout
        activeTimeout = null
        if (timeout != null) {
            handler.removeCallbacks(timeout)
        }
    }

    /**
     * 可用 provider，快排在前。
     *
     * 国产 ROM（实测 vivo）常有自己的融合 provider，`allProviders` 里可能多出额外的名字，
     * 一并纳入候选；拿不到就只认 gps / network。
     */
    private fun providerCandidates(manager: LocationManager): List<String> {
        val known = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        val extra = runCatching {
            manager.allProviders.filter { it != LocationManager.PASSIVE_PROVIDER && it !in known }
        }.getOrDefault(emptyList())
        return (known + extra).filter { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    /** 所有 provider 里最新的那次已知位置。 */
    private fun bestLastKnown(manager: LocationManager): Location? {
        val providers = runCatching { manager.allProviders }.getOrDefault(
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        )
        return providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private fun isFreshEnough(location: Location): Boolean {
        val age = System.currentTimeMillis() - location.time
        return age in 0..lastKnownMaxAgeMs && location.accuracy <= lastKnownMaxAccuracyM
    }

    private companion object {
        const val TAG = "WebAppGeolocation"
        const val LOCATION_TIMEOUT_MS = 12_000L
    }
}

/**
 * 页面 `navigator.geolocation` 的原生后端。
 *
 * 由 [WebAppScreen] 注册成 WebView 的 JS 接口（[WebAppGeolocationScript.BRIDGE_NAME]）：
 * 页面里的 shim 一调 `bridge.request(id)`，这里就（必要时先弹系统授权）取一次定位，
 * 用完 `evaluateJavascript` 把结果回灌给 `window.__cfGeoDeliver(id, payload)`。
 *
 * 权限按需申请：**只有网页真的要定位时才弹**，没用到定位的页面不会被打扰。
 */
internal class WebAppGeolocationController(
    context: Context,
    private val mainHandler: Handler
) {

    private val appContext = context.applicationContext
    private val reader = WebAppLocationReader(appContext, mainHandler)

    private var webView: WebView? = null
    private var requestPermission: ((Array<String>) -> Unit)? = null

    /** 已登记、还没回执的请求（一般是 1 个；同一时刻多个也一起服务掉）。 */
    private val pendingIds = LinkedHashSet<String>()
    private var askingPermission = false

    /** 容器每次挂回来时重新绑一次：WebView 是跨页复用的，桥必须指向当前这一轮。 */
    fun bind(webView: WebView, requestPermission: (Array<String>) -> Unit) {
        this.webView = webView
        this.requestPermission = requestPermission
    }

    /** 容器走了：挂起的请求当场作废，别让页面等一个永远不会来的回执。 */
    fun release() {
        webView = null
        requestPermission = null
        askingPermission = false
        pendingIds.clear()
    }

    @JavascriptInterface
    fun request(id: String) {
        mainHandler.post { startRequest(id) }
    }

    @JavascriptInterface
    fun cancel(id: String) {
        mainHandler.post { pendingIds.remove(id) }
    }

    /** ActivityResult 回执（主线程），把等着的那批请求一起放行或者一起拒掉。 */
    fun onPermissionResult(granted: Boolean) {
        askingPermission = false
        val ids = pendingIds.toList()
        if (!granted) {
            pendingIds.clear()
            ids.forEach { deliver(it, GeoFix.failureJson(PERMISSION_DENIED, "未授予定位权限")) }
            return
        }
        resolvePending()
    }

    private fun startRequest(id: String) {
        if (hasLocationPermission()) {
            pendingIds.add(id)
            resolvePending()
            return
        }
        pendingIds.add(id)
        if (askingPermission) return
        val launch = requestPermission
        if (launch == null) {
            pendingIds.remove(id)
            deliver(id, GeoFix.failureJson(POSITION_UNAVAILABLE, "定位权限不可用"))
            return
        }
        askingPermission = true
        Log.i(TAG, "页面请求定位，先弹系统授权")
        launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun resolvePending() {
        if (pendingIds.isEmpty()) return
        val ids = pendingIds.toList()
        reader.read { fix ->
            mainHandler.post {
                val payload = fix?.toBridgeJson()
                    ?: GeoFix.failureJson(POSITION_UNAVAILABLE, "拿不到位置")
                ids.forEach { id -> if (pendingIds.remove(id)) deliver(id, payload) }
            }
        }
    }

    private fun deliver(id: String, payloadJson: String) {
        val view = webView ?: return
        val script = "window.__cfGeoDeliver && window.__cfGeoDeliver(" +
            jsQuote(id) + ", " + payloadJson + ");"
        view.evaluateJavascript(script, null)
        Log.i(TAG, "定位回执已交给页面：id=$id payload=$payloadJson")
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /** id 由我们自己在 JS 里生成（`cfgeo-1`），这里再兜一层引号转义。 */
    private fun jsQuote(raw: String): String = "'" + raw.replace("\\", "").replace("'", "") + "'"

    companion object {
        private const val TAG = "WebAppGeolocation"

        /** W3C `PositionError.PERMISSION_DENIED`。 */
        const val PERMISSION_DENIED = 1

        /** W3C `PositionError.POSITION_UNAVAILABLE`。 */
        const val POSITION_UNAVAILABLE = 2
    }
}
