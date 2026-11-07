package com.shiro.classflow.data.network.wbu

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 洗浴控水入口解析器（全局扫码与通用链接节点共用）。
 *
 * 把「一卡通鉴权 → 设备正证 → 生成带票据深链」的流程收在一处，
 * 调用方只关心结果，不再各自拼 URL / 调接口：
 * - 1 栋（智能控水）：`CheckKsPos` 正证机号 → 平台换取一次性 ticket → 深链 yktxyyy；
 * - 2-3 栋（lifeService）：平台换取带 `synjones-auth` 的入口地址 → 追加 `scanResult` 原文。
 */
@Singleton
class CampusShowerEntryResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {

    sealed interface Result {
        /**
         * 已生成可直接加载的深链；[pendingAutoScan] 非空时交给 WebAppScreen 自动执行。
         *
         * [nativeShower] 非空表示这台设备已经正证过、确实是**淋浴**（`getDevicesType` = 101）：
         * 调用方应改走原生页 `Destination.ShowerWater`（[initialUrl] 只作为备用地址）。
         *
         * [storeName] 是服务端给的设备位置名（1 栋 `CheckKsPos` 的 `CostDsp`，如「马影河宿舍1栋」；
         * 2-3 栋接口不给，恒为 null）。记录使用时当店铺名用，列表标题就有可读的位置了。
         */
        data class Ready(
            val initialUrl: String,
            val pendingAutoScan: String?,
            val nativeShower: NativeShower? = null,
            val storeName: String? = null,
            /**
             * 这次**真正命中**的系统（`y` / `l`）。
             *
             * 手动输入时入参只有「用户敲的原文 + 他勾的楼栋」，归一后的设备号（去前导零）只有
             * 解析器知道；使用记录（key / 标题）必须按这个字段写，不能再用入参。扫码 / `/s/` 深链等
             * 确定侧入口可以为 null（调用方本来就知道系统与设备号）。
             */
            val resolvedSystem: String? = null,
            /** 真正命中的设备号（1 栋为归一后的机号，2-3 栋为服务端认可的 imei）。同上，写记录用。 */
            val resolvedCode: String? = null,
            /** 真正命中的端口（仅 2-3 栋；`17010737/2` 这类输入会在这里被补上）。 */
            val resolvedPort: String? = null
        ) : Result

        /** 服务端明确判定设备机号无效。 */
        data object InvalidDevice : Result

        /** 网络 / 票据等异常，无法判定。 */
        data class Unavailable(val message: String?) : Result

        /** 本机统一认证会话不可用，需要先登录。 */
        data object NeedLogin : Result
    }

    /**
     * 马影河 1 栋：服务端正证 + 取启动票据。
     *
     * 注意：正证会消耗一张 ticket，启动前必须重新取一张。
     */
    suspend fun resolveYktXyyy(posno: String): Result {
        return try {
            val cardClient = WbuCampusCardClient(context, useVpn = false)
            val yktClient = WbuYktXyyyClient(context, cardClient)
            when (val check = yktClient.checkPosNo(posno)) {
                is WbuYktXyyyClient.CheckResult.Valid ->
                    openYkt(cardClient, posno, check.classLabel, "verified")

                WbuYktXyyyClient.CheckResult.Invalid -> {
                    Log.i(TAG, "ykt posno=$posno rejected by CheckKsPos")
                    Result.InvalidDevice
                }

                is WbuYktXyyyClient.CheckResult.Blocked -> {
                    // 机号是真的，只是这台控水器当前加锁 / 故障。**不挡路**：照样取启动票据进页面，
                    // 由 1 栋 H5 自己提示「设备加锁或故障报警，暂不能交易」。
                    // 过去这里被归成 Unavailable → 用户看到「连接校园服务失败」，等于把设备状态
                    // 说成了网络故障，还把人挡在正确的页面之外。
                    Log.i(TAG, "ykt posno=$posno device blocked (${check.reason}), opening anyway")
                    openYkt(cardClient, posno, check.classLabel, "blocked")
                }

                is WbuYktXyyyClient.CheckResult.Unavailable -> {
                    Log.w(TAG, "ykt posno=$posno check unavailable: ${check.message}")
                    Result.Unavailable(check.message)
                }
            }
        } catch (e: WbuSessionExpiredException) {
            Log.w(TAG, "ykt posno=$posno needs login", e)
            Result.NeedLogin
        } catch (e: Exception) {
            Log.w(TAG, "ykt posno=$posno failed", e)
            Result.Unavailable(e.localizedMessage)
        }
    }

    /**
     * 1 栋：取一张**新的**启动票据并生成落地结果。
     *
     * 正证本身会消耗一张 ticket，所以无论设备是「正常」还是「加锁/故障」，进页面前都要重新取。
     * [stage] 只用于日志（`verified` / `blocked`），便于真机区分这两种放行。
     */
    private suspend fun openYkt(
        cardClient: WbuCampusCardClient,
        posno: String,
        classLabel: String?,
        stage: String
    ): Result {
        val token = cardClient.ensureValidAccessToken()
        val launchUrl = cardClient.resolveAppLaunchUrl(WbuYktXyyyClient.APP_ID, token)
        if (launchUrl.isNullOrBlank()) {
            Log.w(TAG, "ykt posno=$posno $stage but no launch url")
            return Result.Unavailable("未获取到智能控水入口")
        }
        return Result.Ready(
            initialUrl = launchUrl,
            pendingAutoScan = ShowerQrLink.syntheticYktRaw(posno),
            // CostDsp（所属区域名）只在服务端认识这台设备时才有意义；默认值已在
            // WbuYktXyyyClient.classifyCheckKsPos 里过滤掉
            storeName = classLabel,
            resolvedSystem = CampusShowerLink.SYSTEM_YKT,
            resolvedCode = posno
        )
    }

    /**
     * 手动输入 / 判型用的**严格** 2-3 栋正证：服务端不认这台设备就不算成功。
     *
     * 与 [resolveLifeServiceRaw] 的差别（这正是它存在的理由）：那边只把正证当作「要不要走原生页」
     * 的附加判断，`getDevicesType` 失败就静默回落到网页、照样返回 [Result.Ready]。
     * 手输设备码需要能得出「这不是 2-3 栋设备」的结论，否则把 1 栋机号当设备号传进去也会「看起来成功」。
     *
     * 注意它的上限：`getDevicesType` 实测**只回答「这个缴费项是淋浴」**，并不校验设备是否存在
     * （拿 `99999999` 去问也回 `devicesType=101`）。所以这里最多能排除「服务端明确报错」与
     * 「devicesType 为 0」两种情况；设备是否真实存在仍要等页面开单时才知道。
     *
     * 判定口径：
     * - `getDevicesType` 返回 200 且 `devicesType` 非 0 → 命中；
     * - 业务拒绝（`ShowerApiException`）→ [Result.InvalidDevice]；
     * - 会话失效 → [Result.NeedLogin]；其它（网络 / 解析）→ [Result.Unavailable]。
     */
    suspend fun resolveLifeServiceVerified(imei: String, port: String? = null): Result {
        val raw = buildLifeServiceRaw(imei, port) ?: return Result.InvalidDevice
        return try {
            val cardClient = WbuCampusCardClient(context, useVpn = false)
            val token = cardClient.ensureValidAccessToken()
            currentCoroutineContext().ensureActive()
            val launchUrl = cardClient.resolveAppLaunchUrl(ShowerQrLink.LIFE_SERVICE_APP_ID, token)
            if (launchUrl.isNullOrBlank()) {
                Result.Unavailable("未获取到生活服务入口")
            } else {
                val billing = WbuShowerWaterClient.parseBilling(launchUrl)
                if (billing == null) {
                    Result.Unavailable("生活服务入口缺少计费信息")
                } else {
                    val device = lifeServiceDeviceId(raw) ?: return Result.InvalidDevice
                    currentCoroutineContext().ensureActive()
                    val check = try {
                        WbuShowerWaterClient(context).getDevicesType(token, device, billing)
                    } catch (e: WbuShowerWaterClient.ShowerApiException) {
                        // 服务端明确拒绝：设备不存在 / 设备不匹配 —— 这是「换边」的信号，不是网络问题
                        Log.i(TAG, "lifeService device rejected: ${e.message}")
                        return Result.InvalidDevice
                    }
                    currentCoroutineContext().ensureActive()
                    if (check.devicesType <= 0) return Result.InvalidDevice

                    if (check.isShower && !check.hasGears) {
                        Log.i(TAG, "lifeService device is a shower, opening the native screen")
                        Result.Ready(
                            initialUrl = launchUrl,
                            pendingAutoScan = null,
                            nativeShower = NativeShower(
                                deviceId = device,
                                port = port?.takeIf { it.isNotBlank() }
                                    ?: raw.substringAfter(ShowerQrLink.IMEI_PORT_SEPARATOR, "")
                                        .takeIf { it.isNotBlank() },
                                implid = billing.implid,
                                feeitemid = billing.feeitemid
                            ),
                            resolvedSystem = CampusShowerLink.SYSTEM_LIFE,
                            resolvedCode = device,
                            resolvedPort = port?.takeIf { it.isNotBlank() }
                                ?: raw.substringAfter(ShowerQrLink.IMEI_PORT_SEPARATOR, "")
                                    .takeIf { it.isNotBlank() }
                        )
                    } else {
                        // 其它设备类型 / 带档位的淋浴：仍然交给网页（它的 UI 覆盖全部类型）
                        val separator = if (launchUrl.contains("?")) "&" else "?"
                        val encoded = URLEncoder.encode(raw, "UTF-8")
                        Result.Ready(
                            initialUrl = "$launchUrl$separator" + "scanResult=$encoded",
                            pendingAutoScan = null,
                            resolvedSystem = CampusShowerLink.SYSTEM_LIFE,
                            resolvedCode = device,
                            resolvedPort = port?.takeIf { it.isNotBlank() }
                                ?: raw.substringAfter(ShowerQrLink.IMEI_PORT_SEPARATOR, "")
                                    .takeIf { it.isNotBlank() }
                        )
                    }
                }
            }
        } catch (e: WbuSessionExpiredException) {
            Result.NeedLogin
        } catch (e: Exception) {
            Result.Unavailable(e.localizedMessage)
        }
    }

    /**
     * 手动输入设备码：用户已在对话框里选定系统，这里直接走那一侧的入口。
     *
     * 为什么不再「两侧并行」：2-3 栋的 `getDevicesType` 实测**不校验设备是否存在**（拿 1 栋机号
     * `10101`、甚至 `99999999` 去问，都回 `devicesType=101`），而它比 1 栋（要换票、四步接口）
     * 快得多。任何「谁先成功用谁」的并行方案都会被 2-3 栋抢走，等于替用户编造一个成功答案。
     * 系统归属是用户明确勾选的，这里只负责按那一侧解析，并把命中的系统/设备号带回去记使用记录。
     *
     * @param system [CampusShowerLink.SYSTEM_YKT] 或 [CampusShowerLink.SYSTEM_LIFE]
     */
    suspend fun resolveManual(system: String, code: String, port: String? = null): Result {
        currentCoroutineContext().ensureActive()
        val result = when (system.trim().lowercase()) {
            CampusShowerLink.SYSTEM_YKT -> resolveYktXyyy(code.trim())
            CampusShowerLink.SYSTEM_LIFE -> resolveLifeServiceVerified(code.trim(), port)
            else -> Result.InvalidDevice
        }
        currentCoroutineContext().ensureActive()
        return if (result is Result.Ready) {
            result.copy(
                resolvedSystem = result.resolvedSystem ?: system.trim().lowercase(),
                resolvedCode = result.resolvedCode ?: code.trim(),
                resolvedPort = result.resolvedPort ?: port?.takeIf { it.isNotBlank() }
            )
        } else {
            result
        }
    }

    /**
     * 马影河 2-3 栋：水表设备号（+ 可选端口）→ lifeService 深链。
     *
     * 设备号既可以是裸设备号，也可以是整条厂商 / 学校入口链接（`?id=` 载体，协议允许）；
     * 原文怎么拼见 [buildLifeServiceRaw]。
     */
    suspend fun resolveLifeService(imei: String, port: String? = null): Result {
        val raw = buildLifeServiceRaw(imei, port) ?: return Result.InvalidDevice
        return resolveLifeServiceRaw(raw, port)
    }

    /**
     * 设备正证：这台设备在**这个缴费项**下是不是淋浴。
     *
     * 是 → 返回原生页参数；不是、或这一步本身失败（网络 / 启动地址里没有计费上下文）→ null，
     * 调用方照旧走网页。判据取 `devicesType == 101`（页面 `deviceCodeDict` 里的淋浴）。
     */
    private suspend fun resolveNativeShower(
        raw: String,
        port: String?,
        token: String,
        launchUrl: String
    ): NativeShower? {
        val billing = WbuShowerWaterClient.parseBilling(launchUrl) ?: return null
        val device = lifeServiceDeviceId(raw) ?: return null
        return try {
            val check = WbuShowerWaterClient(context).getDevicesType(token, device, billing)
            if (!check.isShower) return null
            // 服务端配了档位（prices 非空）时网页会先让用户选档位，原生页不接管这类设备
            if (check.hasGears) {
                Log.i(TAG, "lifeService device has gear prices, keeping the web page")
                return null
            }
            NativeShower(
                deviceId = device,
                // 端口既可能由调用方给出（`/s/l/{设备}/{端口}`），也可能就在原文里（`<设备>$#$<端口>`）
                port = port?.takeIf { it.isNotBlank() }
                    ?: raw.substringAfter(ShowerQrLink.IMEI_PORT_SEPARATOR, "")
                        .takeIf { it.isNotBlank() },
                implid = billing.implid,
                feeitemid = billing.feeitemid
            )
        } catch (e: Exception) {
            // 正证这一步不该把整条链路带崩：拿不到结论就按老路走网页
            Log.w(TAG, "lifeService device pre-check failed, falling back to the web page", e)
            null
        }
    }

    /**
     * 2-3 栋淋浴原生页参数：设备身份 + 平台给的计费上下文。
     *
     * [implid] / [feeitemid] 由平台启动地址给出（`_implid=63_101&feeitemid=414`），
     * 不是写死的常量 —— 换缴费项就是另一组值。
     */
    data class NativeShower(
        val deviceId: String,
        val port: String?,
        val implid: String,
        val feeitemid: String
    )

    /**
     * 马影河 2-3 栋：直接把扫码原文 / 服务端原文交给 lifeService 页面。
     *
     * 先做一次设备正证：确认是淋浴就走原生页（[NativeShower]），其它设备类型（洗衣机 /
     * 饮水机 / 电吹风…）与正证失败的情形都保持原样 —— 交给网页，网页自己那套 UI 覆盖全部类型。
     *
     * **判型 / 手动输入请用 [resolveLifeServiceVerified]**：本方法把正证失败当作「走网页」，
     * 服务端不认设备时也会返回 [Result.Ready]，不能用来判断「这是不是 2-3 栋设备」。
     */
    suspend fun resolveLifeServiceRaw(raw: String, port: String? = null): Result {
        return try {
            val cardClient = WbuCampusCardClient(context, useVpn = false)
            val token = cardClient.ensureValidAccessToken()
            val launchUrl = cardClient.resolveAppLaunchUrl(ShowerQrLink.LIFE_SERVICE_APP_ID, token)
            if (launchUrl.isNullOrBlank()) {
                Result.Unavailable("未获取到生活服务入口")
            } else {
                resolveNativeShower(raw, port, token, launchUrl)?.let { native ->
                    Log.i(TAG, "lifeService device is a shower, opening the native screen")
                    return Result.Ready(
                        initialUrl = launchUrl,
                        pendingAutoScan = null,
                        nativeShower = native
                    )
                }
                val separator = if (launchUrl.contains("?")) "&" else "?"
                val encoded = URLEncoder.encode(raw, "UTF-8")
                Result.Ready(
                    initialUrl = "$launchUrl$separator" + "scanResult=$encoded",
                    pendingAutoScan = null
                )
            }
        } catch (e: WbuSessionExpiredException) {
            Result.NeedLogin
        } catch (e: Exception) {
            Result.Unavailable(e.localizedMessage)
        }
    }

    companion object {

        private const val TAG = "CampusShowerEntryResolver"

        /**
         * 页面 `handleImei` 的等价取设备号规则：原文带 `$#$` 时取**第一段原文**（页面此时不再解析
         * URL），否则取链接里的 `id` 查询参数。
         */
        fun lifeServiceDeviceId(raw: String): String? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            return if (text.contains(ShowerQrLink.IMEI_PORT_SEPARATOR)) {
                text.substringBefore(ShowerQrLink.IMEI_PORT_SEPARATOR).trim().takeIf { it.isNotEmpty() }
            } else {
                text.toHttpUrlOrNull()?.queryParameter("id")?.trim()?.takeIf { it.isNotEmpty() }
            }
        }

        /** 水表 51 厂商链接基底（页面手输入口生成的原文形态）。 */
        private const val VENDOR_BASE = "http://4gsk.shuibiao51.com"

        /** 设备号长度上限。 */
        private const val MAX_CODE_LENGTH = 256

        /** 端口位数上限。 */
        private const val MAX_PORT_DIGITS = 8

        /**
         * 2-3 栋页面 `handleImei` 等价原文（`applications/js/chunk-b88c7b1e.f1981e59.js`）。
         *
         * 页面两个分支的语义不同，原文必须按分支喂：
         * - **不带端口**：页面走 `imei = getRequest(原文).id`，所以原文要是带 `?id=` 的链接；
         * - **带端口**：页面走 `imei = 第一段原文本身`（**不再解析 URL**），所以第一段必须是
         *   **裸设备号** —— 把厂商链接套在第一段会得到 `http://…?id=<整条链接>$#$2` 这种原文，
         *   页面会把整条链接当 imei 交给 `getDevicesType`，服务端认不出设备。
         *
         * 设备号本身是链接（协议允许把整条厂商链接当设备号）时按页面规则取其中的 `id`：
         * 不带端口直接**原文透传**（与扫码路径一致），带端口则用取到的 id 组 `<id>$#$<port>`。
         *
         * 只接受无空白、无 `$`/`#` 的设备号，避免污染页面解析。
         */
        fun buildLifeServiceRaw(imei: String, port: String? = null): String? {
            val code = imei.trim()
            if (code.isEmpty() || code.length > MAX_CODE_LENGTH) return null
            if (code.any { it.isWhitespace() || it == '$' || it == '#' }) return null

            val url = code.toHttpUrlOrNull()
            val deviceId = if (url == null) code else url.queryParameter("id")?.trim()
            if (deviceId.isNullOrEmpty()) return null
            if (deviceId.any { it.isWhitespace() || it == '$' || it == '#' }) return null

            val portPart = port?.trim().orEmpty()
            if (portPart.isEmpty()) {
                // 不带端口：链接原样透传（页面自己取 ?id=），裸设备号才补厂商链接
                return if (url == null) "$VENDOR_BASE?id=$deviceId" else code
            }
            if (portPart.length > MAX_PORT_DIGITS || !portPart.all { it.isDigit() }) return null
            return deviceId + ShowerQrLink.IMEI_PORT_SEPARATOR + portPart
        }
    }
}
