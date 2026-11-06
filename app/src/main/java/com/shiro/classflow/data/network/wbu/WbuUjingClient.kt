package com.shiro.classflow.data.network.wbu

import android.content.Context
import android.util.Log
import com.shiro.classflow.data.model.wbu.WasherOrderSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * U净 业务与设备交互客户端（基于实测研究笔记 ykt_notes.md 第 10/12/15/16/17 节与实测 HAR）。
 *
 * 核心链路：
 * 1. 一卡通 platformToken -> 解析应用启动地址 (appId 59 饮水 / 45 洗衣) 带出一次性 ticket；
 * 2. parseUrlV2 -> 取得平台 OAuth 授权地址；
 * 3. 平台 OAuth authorize (带 synjones-auth) -> 取得 code；
 * 4. authV2 -> 换得 U净 专属 token；
 * 5. 饮水机：changeWithScan -> currentInfo -> createWaterOrder (orderType=11) -> waterOrderDetail。
 *    欠费单：waterOrderList -> waterOrderDetail -> genPayArgs（一卡通直扣）。
 * 6. 洗衣机：scanWasherCode。
 */
class WbuUjingClient(
    private val context: Context,
    private val cardClient: WbuCampusCardClient = WbuCampusCardClient(context, useVpn = false)
) {
    private val transport: WbuAuthTransport = WbuAuthTransport.getShared(context, false)

    /** 复用统一传输层的证书策略与 Cookie 管理，但禁止自动跟随重定向以便读取 Location。 */
    private val httpClient: OkHttpClient = transport.client.newBuilder()
        .followRedirects(false)
        .build()

    var ujingToken: String? = null
        private set

    var currentUser: UjingUser? = null
        private set

    companion object {
        private const val TAG = "WbuUjingClient"
        const val UJING_API = "https://phoenix.ujing.online:443/api/v1"
        const val UJING_CLIENT_PATH = "wechat_work"
        const val APP_TYPE = 21 // 武汉商学院租户 ID
        const val WATER_APP_ID = 59
        const val WASHER_APP_ID = 45
        const val ORDER_TYPE_SCAN = 11 // 武汉企业微信渠道 = 扫码/一卡通免密

        /** 吹风机（一卡通「自助吹风」子应用，页面 `hairdryer-h5/wechatWorkH5`）。 */
        const val HAIRDRYER_APP_ID = 46

        /**
         * 云端设备（控制盒 / 4G）的 `moduleType`。
         *
         * 判据取自页面自身（`hairdryer-h5` 的 `choseDevice`）：企业微信 / 一卡通容器里
         * `moduleType !== 7` 一律弹「暂不支持蓝牙设备」，只有 `7` 才继续走控制盒下单流程。
         * 也就是说 `7` = 能在一卡通「自助吹风」页面下单（云端 / 4G）；其余（实测本校区为 1）
         * = 手机蓝牙直连，只能交给支付宝 / U净 App 这类能开蓝牙的容器。
         */
        const val HAIRDRYER_MODULE_TYPE_CLOUD = 7

        private const val UA = "Mozilla/5.0 (Linux; Android 16; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130 Mobile Safari/537.36 wxwork/4.1.36 MicroMessenger/7.0.1"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val TERMINAL_STATUS = setOf(50, 51, 54, 62, 63, 64, 65)

        /** 设备正在使用中 / 结账中：唯一值得原地重试的业务码。 */
        const val CODE_DEVICE_BUSY = 2040231

        /**
         * 「您有支付宝订单未结账，请先结账！」。
         *
         * U净 `water-h5` 对 `createWaterOrder` 的这个码有专门分支（只弹这句提示），
         * 说明**服务端会因为一笔欠费直接拒绝下一次下单** —— 这正是「喝水时突然
         * 下不了单」的常见成因，所以上层要认得出它并先去把账结清。
         */
        const val CODE_UNPAID_ORDER = 1532


        fun orderStatusName(status: Int): String = when (status) {
            0 -> "已创建"
            10 -> "开始取水"
            30 -> "结账中"
            50 -> "取水正常完成"
            51 -> "设备离线"
            54 -> "未授权"
            62 -> "结账异常"
            63 -> "手动结束"
            64 -> "提前结束"
            65 -> "支付失败结束"
            else -> if (status >= 50) "订单已结束" else "处理中 ($status)"
        }
    }

    class UjingApiException(val code: Int, override val message: String) : IOException(message)

    data class UjingUser(
        val id: String,
        val nickName: String?,
        val mobile: String?,
        val lbUserId: Long?
    )

    data class WaterServiceSubject(
        val subjectId: Long,
        val subjectName: String,
        val storeName: String?,
        val isSmartCard: Boolean,
        val balanceCents: Long,
        val forceRecharge: Long,
        val rechargeTipAmount: Long
    )

    data class WaterOrderResult(
        val orderId: Long,
        val orderNo: String?,
        val deviceId: String?,
        val orderType: Int
    )

    data class WaterOrderDetail(
        val orderId: Long,
        val orderNo: String?,
        val orderStatus: Int,
        val orderStatusName: String,
        val storeName: String?,
        val deviceNo: String?,
        val orderTypeName: String?,
        val payTypeName: String?,
        val hotWaterMl: Int,
        val warmWaterMl: Int,
        val payPrice: Double,
        val isTerminal: Boolean,
        /** 付款状态（0 未支付 / 1 已支付 / 3 支付中 / 4 支付失败…），见 [UjingPayFlag]。 */
        val payFlag: Int? = null,
        /** 支付渠道码（1 余额 / 2 微信 / 3 支付宝代扣 / 7、8、9 云闪付 / 10 一卡通…）。 */
        val payMode: Int? = null,
        val payModeName: String? = null,
        /** 订单总额（分）。与 [payPrice]（元、实际扣款）不是一回事。 */
        val totalPriceCents: Long = 0L,
        /** 1 = 这单扣一卡通（`waterForceSmartCard`），也是「能不能自动补缴」的判据。 */
        val waterForceSmartCard: Int? = null,
        val deviceId: String? = null
    )

    /**
     * 订单列表项（`water/waterOrderList`）。
     *
     * 只有列表页用得上的字段：金额与付款状态决定「待支付」，`no` 是机号，
     * `storeName` 是店铺。**没有** `waterForceSmartCard` —— 那要再查一次
     * [fetchOrderDetail] 才知道，自动补缴因此是「列表 → 详情」两步。
     */
    data class WaterOrderBrief(
        val orderId: Long,
        val orderNo: String?,
        val storeName: String?,
        val deviceNo: String?,
        val createdAt: String?,
        /** 实付金额（元）。 */
        val payPrice: Double,
        val payFlag: Int?,
        val payMode: Int?,
        val payModeName: String?
    )

    /**
     * 洗衣机设备码核验结果。
     *
     * [availability] 是唯一可靠的判定；[status] / [orderId] / [serverReason] / [merchantMobile]
     * 是原始字段，用于展示更具体的原因（见 [WasherUnavailableReason]）。
     */
    data class WasherScanResult(
        val availability: WasherAvailability,
        val status: Int?,
        val orderId: Long?,
        val serverReason: String?,
        val merchantMobile: String?,
        val lastUseDeviceId: String?,
        val rawData: JSONObject
    )

    /**
     * 洗衣机设备详情（`washer/devices/program/info?deviceId=`）里与命名相关的字段。
     *
     * [deviceNo] 是纯机号（`000002`），标题就是「店铺名-机号」（如「南B-11-02」）；
     * [deviceTypeName]（`10kg滚筒机`）只用于日志，刻意不进标题。
     * [storeName]（如「南B-11」）与 [serviceSubjectName]（如「武汉商学院-南」）是
     * 校园设备管理页的标题 / 小标题来源（实测 2026-11-04）。
     */
    data class WasherDeviceInfo(
        val deviceId: String,
        val deviceNo: String?,
        val deviceTypeName: String?,
        val storeName: String?,
        val serviceSubjectName: String?
    )

    /**
     * 吹风机扫码结果（`controlBox/devices/scan`）。
     *
     * [createOrderEnabled] 只表示「现在能不能下单」，不能当设备类型用：
     * 蓝牙设备的控制盒记录同样会回 true（实测本校区全部如此），
     * 是蓝牙还是云端由 [HairdryerHubInfo.moduleType] 决定。
     */
    data class HairdryerScanResult(
        val deviceId: String?,
        val createOrderEnabled: Boolean,
        val status: Int?,
        val reason: String?
    )

    /**
     * 吹风机控制盒（hub）信息（`controlBox/devices/{deviceId}/info`）。
     *
     * 一台控制盒带左右两个子机（`subDevice`，status = 3 表示停用），所以扫码拿到的是 hub 的
     * [deviceId]，而不是某一台吹风机。
     */
    data class HairdryerHubInfo(
        val hubDeviceTypeId: Int,
        val hubDeviceTypeName: String?,
        val moduleType: Int,
        val macAddress: String?,
        val no: String?,
        /** 未停用的子机数量（页面会把 status = 3 的子机过滤掉）。 */
        val availableSubDevices: Int,
        /**
         * 第一台可用子机的 ID（左机优先）。
         *
         * 页面点进某一台机之后才会用到它（`#/placeOrder?subDeviceId=`），
         * 这里取来只为查店铺名（`controlBox/devices/{subDeviceId}/model`）。
         */
        val availableSubDeviceId: String? = null
    ) {
        /** 云端（控制盒 / 4G）设备：一卡通「自助吹风」页面能下单。 */
        val isCloud: Boolean get() = moduleType == HAIRDRYER_MODULE_TYPE_CLOUD
    }

    /**
     * 子机计费 / 门店信息（`controlBox/devices/{subDeviceId}/model`）。
     *
     * 店铺名与服务主体名挂在这一层（页面在选择程序那一步才查），也是吹风机记录列表
     * 大/小标题的来源：[storeName]（如「南B-11」）、[subjectName]（如「武汉商学院26-本部」）。
     */
    data class HairdryerModelInfo(
        val storeName: String?,
        val subjectName: String?
    )

    private fun ujHeaders(appCode: String = "COA"): Map<String, String> = buildMap {
        put("Content-Type", "application/json; charset=utf-8")
        put("x-app-code", appCode)
        put("x-app-version", "1.0.34")
        put("Origin", "https://static.ujing.com.cn")
        put("Referer", "https://static.ujing.com.cn/")
        put("User-Agent", UA)
        ujingToken?.let { put("Authorization", "Bearer $it") }
    }

    /**
     * 建立 U净 会话（跨平台 SSO 换票）。
     *
     * @param platformToken 一卡通平台的 access_token
     * @param appId 平台子应用 ID（59=饮水，45=洗衣，46=吹风机）
     */
    suspend fun connect(platformToken: String, appId: Int = WATER_APP_ID): UjingUser = withContext(Dispatchers.IO) {
        // 1. 获取带 ticket 的平台启动跳转地址
        val launchUrl = cardClient.resolveAppLaunchUrl(appId, platformToken)
            ?: throw IOException("未能获取平台子应用 (appId=$appId) 启动地址")

        val appCode = if (appId == WASHER_APP_ID) "BO" else "COA"

        // 2. parseUrlV2 认票，返回平台 OAuth 授权地址
        val parseBody = JSONObject().apply {
            put("url", launchUrl)
            put("appType", APP_TYPE)
        }
        val parseReq = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/third-party/parseUrlV2")
            .post(parseBody.toString().toRequestBody(JSON_MEDIA))
            .apply { ujHeaders(appCode).forEach { (k, v) -> header(k, v) } }
            .build()

        val authUrl = httpClient.newCall(parseReq).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            val data = json.optJSONObject("data") ?: throw IOException("parseUrlV2 响应异常: $bodyStr")
            data.optString("authUrl").takeIf { it.isNotBlank() }
                ?: throw IOException("parseUrlV2 未返回授权地址: $bodyStr")
        }

        // 3. 携带平台身份访问 OAuth 授权地址，换取 code
        val sep = if (authUrl.contains("?")) "&" else "?"
        val authedUrl = "$authUrl${sep}synjones-auth=" + URLEncoder.encode("bearer $platformToken", "UTF-8")
        val codeReq = Request.Builder()
            .url(authedUrl)
            .header("User-Agent", UA)
            .header("synAccessSource", "h5")
            .get()
            .build()

        val code = httpClient.newCall(codeReq).execute().use { resp ->
            val location = resp.header("Location").orEmpty()
            val match = Regex("[?&]code=([^&#]+)").find(location)
            match?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
                ?: throw IOException("平台 OAuth 未签发 code (status=${resp.code}, Location=$location)")
        }

        // 4. authV2 使用 code 换取 U净 独立 Token
        val authBody = JSONObject().apply {
            put("code", code)
            put("appType", APP_TYPE)
            put("loginWay", 1)
            put("userId", 0)
        }
        val authReq = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/third-party/authV2")
            .post(authBody.toString().toRequestBody(JSON_MEDIA))
            .apply { ujHeaders(appCode).forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(authReq).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            val data = json.optJSONObject("data") ?: throw IOException("authV2 换票异常: $bodyStr")
            val token = data.optString("token")
            if (token.isBlank()) throw IOException("authV2 未返回 token: $bodyStr")

            ujingToken = token
            val user = UjingUser(
                id = data.optString("id"),
                nickName = data.optString("nickName").takeIf { it.isNotBlank() },
                mobile = data.optString("mobile").takeIf { it.isNotBlank() },
                lbUserId = data.optLong("lbUserId", 0L).takeIf { it > 0 }
            )
            currentUser = user
            user
        }
    }

    /**
     * 直接复用一份已有的 U净 token（后台轮询 / 页面刷新共用同一份会话）。
     *
     * 与 [connect] 的区别：不跑换票链路，只把 token 装上。过期由 401 重试兜底。
     */
    fun adoptSession(token: String) {
        ujingToken = token
    }

    /**
     * 绑定扫码贴纸对应的取水点（服务主体）。
     */
    suspend fun bindWaterPoint(cd: String): WaterServiceSubject = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("cd", cd) }
        val req = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/water/serviceSubject/changeWithScan")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .apply { ujHeaders("COA").forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            if (json.optInt("code") != 0) {
                val msg = json.optString("message").ifBlank { "绑定取水点失败" }
                throw IOException(msg)
            }
            val data = json.optJSONObject("data") ?: throw IOException("绑定取水点未返回数据")
            WaterServiceSubject(
                subjectId = data.optLong("newServiceSubjectId"),
                subjectName = data.optString("newServiceSubjectName"),
                storeName = data.optString("storeName").takeIf { it.isNotBlank() },
                isSmartCard = data.optInt("waterForceSmartCard", 0) == 1,
                balanceCents = data.optLong("balance", 0L),
                forceRecharge = data.optLong("forceRecharge", 0L),
                rechargeTipAmount = data.optLong("rechargeTipAmount", 0L)
            )
        }
    }

    /**
     * 查询当前取水点信息与一卡通免密状态。
     */
    suspend fun getCurrentWaterInfo(): WaterServiceSubject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/water/serviceSubject/currentInfo")
            .get()
            .apply { ujHeaders("COA").forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            val data = json.optJSONObject("data") ?: throw IOException("获取取水点信息失败")
            WaterServiceSubject(
                subjectId = data.optLong("ServiceSubjectId"),
                subjectName = data.optString("ServiceSubjectName"),
                storeName = null,
                isSmartCard = data.optInt("waterForceSmartCard", 0) == 1,
                balanceCents = data.optLong("balance", 0L),
                forceRecharge = data.optLong("forceRecharge", 0L),
                rechargeTipAmount = data.optLong("rechargeTipAmount", 0L)
            )
        }
    }

    /**
     * 下单出水（orderType=11 武汉企微扫码/免密出水）。
     * 若遇到 2040231「设备正在使用中或者结账中」，自动重试。
     */
    suspend fun dispenseWater(cd: String, retries: Int = 4, gapMillis: Long = 3000L): WaterOrderResult = withContext(Dispatchers.IO) {
        var lastMsg = ""
        for (attempt in 0 until retries) {
            val body = JSONObject().apply {
                put("deviceId", cd)
                put("orderType", ORDER_TYPE_SCAN)
            }
            val req = Request.Builder()
                .url("$UJING_API/$UJING_CLIENT_PATH/water/createWaterOrder")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .apply { ujHeaders("COA").forEach { (k, v) -> header(k, v) } }
                .build()

            val outcome = httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val code = json.optInt("code")
                if (code == 0) {
                    val data = json.optJSONObject("data") ?: throw IOException("出水订单创建未返回数据")
                    return@use Result.success(
                        WaterOrderResult(
                            orderId = data.optLong("orderId"),
                            orderNo = data.optString("orderNo"),
                            deviceId = data.optString("deviceId"),
                            orderType = data.optInt("orderType", ORDER_TYPE_SCAN)
                        )
                    )
                }
                val msg = json.optString("message").ifBlank { "出水下单失败 (code=$code)" }
                Result.failure(UjingApiException(code, msg))
            }

            if (outcome.isSuccess) {
                return@withContext outcome.getOrThrow()
            }

            val err = outcome.exceptionOrNull() as? UjingApiException
            val code = err?.code ?: 0
            lastMsg = err?.message ?: "未知错误"
            if (code != CODE_DEVICE_BUSY) {
                // 非设备忙状态直接抛错退出。带业务码抛出（不是裸 IOException）：
                // 上层要靠 code 认出「有未结账订单」这类**可处理**的失败，而不是一律当网络错。
                throw err ?: IOException(lastMsg)
            }

            Log.i(TAG, "Device $cd busy ($CODE_DEVICE_BUSY), retry ${attempt + 1}/$retries in ${gapMillis}ms")
            delay(gapMillis)
        }

        throw IOException(lastMsg.ifBlank { "设备正在使用中，请稍后重试" })
    }

    /**
     * 查询订单详情与实时状态。
     */
    suspend fun fetchOrderDetail(orderId: Long): WaterOrderDetail = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("orderId", orderId) }
        val req = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/water/waterOrderDetail")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .apply { ujHeaders("COA").forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            val data = json.optJSONObject("data") ?: throw IOException("获取订单详情失败: $bodyStr")
            val status = data.optInt("orderStatus", -1)
            val statusName = data.optString("orderStatusName").ifBlank { orderStatusName(status) }
            val terminal = status in TERMINAL_STATUS

            WaterOrderDetail(
                orderId = orderId,
                orderNo = data.optString("orderNo"),
                orderStatus = status,
                orderStatusName = statusName,
                storeName = data.optString("storeName").takeIf { it.isNotBlank() },
                deviceNo = data.optString("deviceNo").takeIf { it.isNotBlank() },
                orderTypeName = data.optString("orderTypeName").takeIf { it.isNotBlank() },
                payTypeName = data.optString("payTypeName").takeIf { it.isNotBlank() },
                hotWaterMl = data.optInt("hotWaterML", 0),
                warmWaterMl = data.optInt("warmWaterML", 0),
                payPrice = data.optDouble("payPrice", 0.0),
                isTerminal = terminal,
                // 注意用 has() 区分「字段缺失」与「值为 0」：0 是「未支付」，缺失是「不知道」。
                payFlag = if (data.has("payFlag")) data.optInt("payFlag") else null,
                payMode = if (data.has("payMode")) data.optInt("payMode") else null,
                payModeName = data.optString("payModeName").takeIf { it.isNotBlank() },
                totalPriceCents = data.optLong("totalPrice", 0L),
                waterForceSmartCard = if (data.has("waterForceSmartCard")) {
                    data.optInt("waterForceSmartCard")
                } else {
                    null
                },
                deviceId = data.optString("deviceId").takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * 订单列表（`water/waterOrderList`，`{skip, limit}`）。
     *
     * 响应形态实测是 **`data` 直接为数组**（官方页面 `(await api({skip,limit})).map(...)`），
     * 但为免服务端换皮，`data.list` / `data.records` 也一并兜住 —— 拿不到数组就当空列表，
     * 调用方（自动补缴）本来就把「查不到」当无事发生。
     */
    suspend fun listWaterOrders(skip: Int = 0, limit: Int = 20): List<WaterOrderBrief> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("skip", skip)
            put("limit", limit)
        }
        val req = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/water/waterOrderList")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .apply { ujHeaders("COA").forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            if (json.optInt("code") != 0) {
                val msg = json.optString("message").ifBlank { "查询订单列表失败 (code=${json.optInt("code")})" }
                throw UjingApiException(json.optInt("code"), msg)
            }
            val array = json.optJSONArray("data")
                ?: json.optJSONObject("data")?.optJSONArray("list")
                ?: json.optJSONObject("data")?.optJSONArray("records")
                ?: return@use emptyList()

            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        WaterOrderBrief(
                            orderId = item.optLong("id", item.optLong("orderId", 0L)),
                            orderNo = item.optString("orderNo").takeIf { it.isNotBlank() },
                            storeName = item.optString("storeName").takeIf { it.isNotBlank() },
                            deviceNo = item.optString("no").takeIf { it.isNotBlank() }
                                ?: item.optString("deviceNo").takeIf { it.isNotBlank() },
                            createdAt = item.optString("createAt").takeIf { it.isNotBlank() },
                            payPrice = item.optDouble("payPrice", 0.0),
                            payFlag = if (item.has("payFlag")) item.optInt("payFlag") else null,
                            payMode = if (item.has("payMode")) item.optInt("payMode") else null,
                            payModeName = item.optString("payModeName").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
    }

    /**
     * 结清一笔欠费订单（`water/genPayArgs`）。
     *
     * **只走一卡通直扣**（`channel = campusOneCardPay`）：U净 官方未支付页对
     * `waterForceSmartCard == 1` 的订单就是这么做的，请求一发出钱就扣了，
     * 页面拿到响应直接宣布「支付成功」，没有任何 SDK 回调或二次确认。
     *
     * 云闪付（`unionPay` 分支）与微信/支付宝**刻意不提供**：前者要 `upsdk`、
     * 后者要各自的钱包，自建容器里起不来，硬调只会让用户以为付了。
     *
     * 业务码非 0 抛 [UjingApiException]，把服务端原话交给调用方展示。
     */
    suspend fun settleWaterOrder(
        orderNo: String,
        chooseSign: Int = 1,
        type: String = "payOrder",
    ): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("channel", "campusOneCardPay")
            put("orderNo", orderNo)
            put("chooseSign", chooseSign)
            put("type", type)
        }
        val req = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/water/genPayArgs")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .apply { ujHeaders("COA").forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            val code = json.optInt("code")
            if (code != 0) {
                val msg = json.optString("message").ifBlank { "补缴失败 (code=$code)" }
                throw UjingApiException(code, msg)
            }
        }
    }

    /**
     * 洗衣机设备码核验（扫描二维码原文）。
     * 对应 U净 washer-h5 的 devices/scanWasherCode 接口。
     *
     * `result.createOrderEnabled` 只是「现在能不能下单」，不能当成「设备在线」：
     * 离线 / 被占用 / 故障 / 停用 / 码不存在都会是 false，具体原因由 [WasherAvailability] 给出。
     */
    suspend fun scanWasherCode(qrcode: String): WasherScanResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("qrCode", qrcode)
            put("scanWay", 0)
        }
        val req = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/devices/scanWasherCode")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .apply { ujHeaders("BO").forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            if (json.optInt("code") != 0) {
                val msg = json.optString("message").ifBlank { "洗衣机扫码未成功 (code=${json.optInt("code")})" }
                // 带业务码抛出：调用方可据此区分「服务端明确拒绝」与网络异常
                throw UjingApiException(json.optInt("code"), msg)
            }
            val data = json.optJSONObject("data") ?: throw IOException("洗衣机扫码未返回数据: $bodyStr")
            val result = data.optJSONObject("result")

            // 注意用 has() 区分「字段缺失」与「字段为 0/false」：缺失不能默认成可用
            val createOrderEnabled =
                if (result?.has("createOrderEnabled") == true) result.optBoolean("createOrderEnabled") else null
            val status = if (result?.has("status") == true) result.optInt("status") else null
            val orderId = if (result?.has("orderId") == true) result.optLong("orderId", 0L) else 0L
            val deviceId = result?.optString("deviceId").takeIf { !it.isNullOrBlank() }
            val merchantMobile = result?.optString("mobile").takeIf { !it.isNullOrBlank() }
            val availability = resolveWasherAvailability(
                createOrderEnabled = createOrderEnabled,
                status = status,
                hasOrderId = orderId > 0L,
                hasDeviceId = deviceId != null,
                merchantMobile = merchantMobile
            )

            WasherScanResult(
                availability = availability,
                status = status,
                orderId = orderId.takeIf { it > 0L },
                serverReason = result?.optString("reason").takeIf { !it.isNullOrBlank() },
                merchantMobile = merchantMobile,
                lastUseDeviceId = deviceId,
                rawData = data
            )
        }
    }

    /**
     * 洗衣机设备详情（washer-h5 首页 `fetchDeviceProgramById` 用的同一接口）。
     *
     * `scanWasherCode` 只给 [WasherScanResult.lastUseDeviceId]，不给任何名字；
     * H5 拿到 deviceId 后再查这一步才有「000002-10kg滚筒机 / 南B-11」。
     * 仅供后台补名使用，失败返 null 不阻断使用流程。
     */
    suspend fun washerDeviceInfo(deviceId: String): WasherDeviceInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$UJING_API/$UJING_CLIENT_PATH/washer/devices/program/info?deviceId=$deviceId")
                .get()
                .apply { ujHeaders("BO").forEach { (k, v) -> header(k, v) } }
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                if (json.optInt("code") != 0) return@runCatching null
                val data = json.optJSONObject("data") ?: return@runCatching null
                WasherDeviceInfo(
                    deviceId = data.optString("deviceId").takeIf { it.isNotBlank() } ?: deviceId,
                    deviceNo = data.optString("deviceNo").takeIf { it.isNotBlank() },
                    deviceTypeName = data.optString("deviceTypeName").takeIf { it.isNotBlank() },
                    storeName = data.optString("storeName").takeIf { it.isNotBlank() },
                    serviceSubjectName = data.optString("serviceSubjectName").takeIf { it.isNotBlank() }
                )
            }
        }.getOrNull()
    }

    /**
     * 进行中的洗衣机订单（`GET orders/running`，washer-h5「我的订单」的进行中页签）。
     *
     * 没有进行中订单时服务端回空数组，不抛异常。
     */
    suspend fun fetchRunningWasherOrders(): List<WasherOrderSummary> = withContext(Dispatchers.IO) {
        getOrderList("$UJING_API/$UJING_CLIENT_PATH/orders/running")
    }

    /**
     * 已完成的洗衣机订单（`GET orders/history?page=&size=`）。
     *
     * 洗衣账单的数据源。分页从 **1** 开始（与 washer-h5 一致），不是 0。
     */
    suspend fun fetchWasherOrderHistory(
        page: Int = 1,
        size: Int = 20,
    ): List<WasherOrderSummary> = withContext(Dispatchers.IO) {
        getOrderList("$UJING_API/$UJING_CLIENT_PATH/orders/history?page=$page&size=$size")
    }

    /**
     * 洗衣机订单详情（`GET orders/{orderId}/detail?orderId=`）。
     *
     * 进度页的权威数据源：列表接口不带 `remainTime` / `workTime`，详情才有。
     * 订单不存在 / 不是自己的单时返 null（服务端 code 非 0）。
     */
    suspend fun fetchWasherOrderDetail(orderId: Long): WasherOrderSummary? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$UJING_API/$UJING_CLIENT_PATH/orders/$orderId/detail?orderId=$orderId")
                .get()
                .apply { ujHeaders("BO").forEach { (k, v) -> header(k, v) } }
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    throw UjingApiException(resp.code, "U净 登录态已失效")
                }
                val bodyStr = resp.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                if (json.optInt("code") != 0) return@use null
                WasherOrderParser.parseOrderDetail(bodyStr)
            }
        }.getOrNull()
    }

    /** 订单列表接口的公共部分：鉴权头 + 解析 + 401/403 抛业务异常。 */
    private fun getOrderList(url: String): List<WasherOrderSummary> {
        val req = Request.Builder()
            .url(url)
            .get()
            .apply { ujHeaders("BO").forEach { (k, v) -> header(k, v) } }
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw UjingApiException(resp.code, "U净 登录态已失效")
            }
            val bodyStr = resp.body?.string().orEmpty()
            val code = runCatching { JSONObject(bodyStr).optInt("code", -1) }.getOrDefault(-1)
            if (code != 0) {
                throw UjingApiException(code, "洗衣订单查询失败 (code=$code)")
            }
            return WasherOrderParser.parseOrderList(bodyStr)
        }
    }

    /**
     * 吹风机扫码（`controlBox/devices/scan`，`hairdryer-h5` 首页「扫一扫」用的就是它）。
     *
     * 响应形如（实测 2026-10-08）：
     * - 蓝牙设备：`{"deviceId":"7c74…","createOrderEnabled":true,"status":0,"reason":""}`
     * - 未绑定控制盒：`{"deviceId":"","createOrderEnabled":false,"status":5,"reason":"当前设备未绑定。"}`
     * - 左右机都不可用：`code = 1116`「当前两台设备不可用，请更换至其他设备!」（抛 [UjingApiException]）
     *
     * 注意 [HairdryerScanResult.createOrderEnabled] 对蓝牙设备也可以是 true —— 设备是蓝牙还是
     * 云端要看 [getHairdryerHubInfo] 的 `moduleType`，别用这个字段判类型。
     */
    suspend fun scanHairdryerCode(qrCode: String): HairdryerScanResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply { put("qrCode", qrCode) }
        val req = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/controlBox/devices/scan")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .apply { ujHeaders("COA").forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            if (json.optInt("code") != 0) {
                val msg = json.optString("message").ifBlank { "吹风机扫码未成功 (code=${json.optInt("code")})" }
                throw UjingApiException(json.optInt("code"), msg)
            }
            val data = json.optJSONObject("data") ?: throw IOException("吹风机扫码未返回数据: $bodyStr")
            HairdryerScanResult(
                deviceId = data.optString("deviceId").takeIf { it.isNotBlank() },
                createOrderEnabled = data.optBoolean("createOrderEnabled"),
                status = if (data.has("status")) data.optInt("status") else null,
                reason = data.optString("reason").takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * 吹风机控制盒信息（`controlBox/devices/{deviceId}/info`）：
     * 一台控制盒 + 左右两个子机，[HairdryerHubInfo.moduleType] 即「蓝牙 / 云端」的判据。
     */
    suspend fun getHairdryerHubInfo(deviceId: String): HairdryerHubInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/controlBox/devices/${deviceId}/info")
            .get()
            .apply { ujHeaders("COA").forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            if (json.optInt("code") != 0) {
                val msg = json.optString("message").ifBlank { "吹风机设备信息未取到 (code=${json.optInt("code")})" }
                throw UjingApiException(json.optInt("code"), msg)
            }
            val data = json.optJSONObject("data") ?: throw IOException("吹风机设备信息未返回数据: $bodyStr")
            val subDevices = data.optJSONArray("subDevice")
            var available = 0
            var firstAvailableId: String? = null
            if (subDevices != null) {
                for (i in 0 until subDevices.length()) {
                    val sub = subDevices.optJSONObject(i) ?: continue
                    // 与页面一致：status = 3 是停用，其余（含占用）都算「有这台机」
                    if (sub.optInt("status") != 3) {
                        available++
                        if (firstAvailableId == null) {
                            firstAvailableId = sub.optString("subDeviceId").takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
            HairdryerHubInfo(
                hubDeviceTypeId = data.optInt("hubDeviceTypeId"),
                hubDeviceTypeName = data.optString("hubDeviceTypeName").takeIf { it.isNotBlank() },
                moduleType = data.optInt("moduleType"),
                macAddress = data.optString("macAddress").takeIf { it.isNotBlank() },
                no = data.optString("no").takeIf { it.isNotBlank() },
                availableSubDevices = available,
                availableSubDeviceId = firstAvailableId
            )
        }
    }

    /**
     * 子机计费 / 门店信息（`controlBox/devices/{subDeviceId}/model`）。
     *
     * 只关心 [HairdryerModelInfo.storeName] / [HairdryerModelInfo.subjectName]：
     * 一卡通页面把这两项拼在设备卡片上（`store.serviceSubjectName + store.storeName`），
     * 也是吹风机记录列表的大小标题。取不到就当没有，不影响任何跳转。
     */
    suspend fun getHairdryerModel(subDeviceId: String): HairdryerModelInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$UJING_API/$UJING_CLIENT_PATH/controlBox/devices/${subDeviceId}/model")
            .get()
            .apply { ujHeaders("COA").forEach { (k, v) -> header(k, v) } }
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            val json = JSONObject(bodyStr)
            if (json.optInt("code") != 0) {
                val msg = json.optString("message").ifBlank { "吹风机门店信息未取到 (code=${json.optInt("code")})" }
                throw UjingApiException(json.optInt("code"), msg)
            }
            val store = json.optJSONObject("data")?.optJSONObject("store")
            HairdryerModelInfo(
                storeName = store?.optString("storeName")?.takeIf { it.isNotBlank() },
                subjectName = store?.optString("serviceSubjectName")?.takeIf { it.isNotBlank() }
            )
        }
    }
}
