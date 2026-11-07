package com.shiro.classflow.data.network.wbu

import android.content.Context
import android.util.Log
import com.shiro.classflow.data.model.wbu.CampusDeviceEntry
import com.shiro.classflow.data.model.wbu.CampusDeviceKind
import com.xingheyuzhuan.shiguangschedule.data.repository.CampusDeviceHubRepository
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 洗衣机入口解析。
 *
 * `/wm/{uuid}`（NFC 标签 / 网页深链）只带设备码，直接打开一卡通门户并不会自动进入洗衣机流程；
 * 这里复用扫码链路的同一套接口（登录态 → connect → 可用性检测 → 解析 App 启动地址），
 * 得到可直达的洗衣机 H5 地址。
 *
 * 语义与扫码链路保持一致：
 * - [Result.Ready]：拿到直达地址（可下单，或扫码校验被业务规则拒绝但可交由 H5 提示）；
 * - [Result.Unavailable]：服务端明确判定当前不可下单，按 [WasherUnavailableReason]
 *   给出准确原因（离线 / 被占用 / 故障 / 停用 / 码不存在），而不是一律说「设备离线」；
 * - [Result.Failed]：未登录 / 网络异常等，调用方回退到门户自举方案。
 */
object WasherEntryResolver {

    private const val TAG = "WasherEntryResolver"

    sealed interface Result {
        /** 可直接打开的洗衣机 H5 地址（已带上 scanResult）。 */
        data class Ready(val url: String) : Result

        /** 设备当前不可下单，[availability] 里带原因（与商家电话）。 */
        data class Unavailable(val availability: WasherAvailability.Unavailable) : Result

        /** 未登录、网络异常等，需要回退门户。 */
        data object Failed : Result
    }

    suspend fun resolve(context: Context, washerRaw: String, useVpn: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                val encodedRaw = URLEncoder.encode(washerRaw, "UTF-8")
                val cardClient = WbuCampusCardClient(context.applicationContext, useVpn)
                val ujingClient = WbuUjingClient(context.applicationContext, cardClient)
                val token = cardClient.ensureValidAccessToken()
                ujingClient.connect(token, appId = WbuUjingClient.WASHER_APP_ID)

                // 与扫码链路一致：扫码接口抛业务异常（如「不在营业时间」）不阻断直达，交给 H5 提示。
                // 只有服务端给出**明确判定**时才拦下来；异常/字段缺失不再默认「在线」。
                val scan = runCatching { ujingClient.scanWasherCode(washerRaw) }
                    .onFailure { Log.w(TAG, "scanWasherCode 被拒，仍按扫码链路兜底直达 H5", it) }
                    .getOrNull()
                // 补名不占状态：设备被占用 / 离线时 scan 里照样带着 deviceId，
                // 名字该补还是要补（不然「正在运行中」这台机器的标题永远是 uuid 尾号）。
                if (scan != null) {
                    enrichNames(context, washerRaw, scan.lastUseDeviceId, ujingClient)
                }
                val unavailable = scan?.availability as? WasherAvailability.Unavailable
                if (scan != null && unavailable != null) {
                    Log.i(TAG, "洗衣机不可下单: ${unavailable.reason} (status=${scan.status}, raw=${scan.rawData})")
                    return@runCatching Result.Unavailable(unavailable)
                }

                val launchUrl = cardClient.resolveAppLaunchUrl(WbuUjingClient.WASHER_APP_ID, token)
                Log.i(TAG, "resolveAppLaunchUrl=$launchUrl")
                if (launchUrl.isNullOrBlank()) return@runCatching Result.Failed
                val separator = if (launchUrl.contains("?")) "&" else "?"
                Result.Ready("$launchUrl${separator}scanResult=$encodedRaw")
            }.onFailure {
                Log.w(TAG, "解析洗衣机入口失败: $washerRaw", it)
            }.getOrDefault(Result.Failed)
        }

    /**
     * 把这次洗衣机使用记进「校园设备管理」（拿到直达地址后调用）。
     *
     * 这是一个 object（非 Hilt 注入），记录经 [CampusDeviceHubRepositoryHolder] 拿单例。
     * 记录失败只打日志：使用记录不能把洗衣机直达带崩。
     */
    fun recordUse(context: Context, washerRaw: String) {
        val key = uuidOf(washerRaw) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                CampusDeviceHubRepositoryHolder.get(context)?.record(washerEntry(key, washerRaw))
            }.onFailure { Log.w(TAG, "记录洗衣机使用失败", it) }
        }
    }

    /**
     * 后台补全洗衣机记录的名字（`washer/devices/program/info`，实测 2026-11-04）。
     *
     * `scanWasherCode` 只回 deviceId（不是 uuid），名字要拿 deviceId 再查一步才有：
     * storeName「南B-11」、纯机号 deviceNo「000002」、主体名「武汉商学院-南」。
     * 型号名（`deviceTypeName`「10kg滚筒机」）刻意不进标题 —— 标题只放「店铺名-机号」。
     *
     * 两条入口路径（扫码 / `/wm/` 深链）共用。补名是锦上添花：任何失败只打日志。
     *
     * @param ujingClient 调用方若已建好会话（扫码路径本来就连着）就复用，避免再跑一遍换票。
     */
    fun enrichNames(
        context: Context,
        washerRaw: String,
        lastUseDeviceId: String?,
        ujingClient: WbuUjingClient? = null,
    ) {
        val key = uuidOf(washerRaw) ?: return
        if (lastUseDeviceId.isNullOrBlank()) return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                // record 与本方法并发（扫码路径两者同时起），等记录先落库再补名
                val repo = CampusDeviceHubRepositoryHolder.get(appContext) ?: return@launch
                var recorded = false
                for (attempt in 0 until 5) {
                    recorded = repo.entriesOf(CampusDeviceKind.WASHER).first()
                        .any { it.key == key }
                    if (recorded) break
                    delay(600)
                }
                if (!recorded) return@launch

                val client = ujingClient ?: run {
                    val cardClient = WbuCampusCardClient(appContext)
                    WbuUjingClient(appContext, cardClient).also {
                        it.connect(cardClient.ensureValidAccessToken(), WbuUjingClient.WASHER_APP_ID)
                    }
                }
                val info = client.washerDeviceInfo(lastUseDeviceId) ?: return@launch
                repo.fillNames(
                    CampusDeviceKind.WASHER,
                    key = key,
                    storeName = info.storeName,
                    subjectName = info.serviceSubjectName,
                    // 纯机号（000002），不是「000002-10kg滚筒机」：标题只需要前者
                    deviceNo = info.deviceNo
                )
                Log.i(TAG, "洗衣机补名: ${info.storeName} / ${info.deviceNo}")
            }.onFailure { Log.w(TAG, "洗衣机补名失败（不影响使用）: $lastUseDeviceId", it) }
        }
    }

    /** 从小天鹅原文里取 uuid（`?uuid=` 参数）；不是小天鹅链接时按原文末段兜底。 */
    fun uuidOf(washerRaw: String): String? =
        washerRaw.toHttpUrlOrNull()?.queryParameter("uuid")?.trim()?.takeIf { it.isNotBlank() }
            ?: Regex("uuid=([^&#]+)").find(washerRaw)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** 洗衣机记录条目（QrScanViewModel 与深链两条路共用，保证同一次使用只记一条）。 */
    fun washerEntry(key: String, raw: String): CampusDeviceEntry = CampusDeviceEntry(
        kind = CampusDeviceKind.WASHER,
        key = key,
        raw = raw,
        // 刻意不预置 deviceNo：扫码只给 deviceId，任何「先塞个 uuid 尾号占位」的写法
        // 都会被 fillNames 的「只补空缺」规则挡住，导致真正的机号（000002）永远写不进去。
        // 补名失败时标题会落到 seq 的「洗衣机 N」，比 uuid 尾巴可读。
    )
}

/**
 * [CampusDeviceHubRepository] 的进程级持有者：给 object 形态的解析器
 * （`WasherEntryResolver` / `CampusShowerEntryResolver`）一个不依赖 Hilt 注入的取用口。
 *
 * 必须早于任何记录调用前初始化（MainActivity `onCreate`），取不到时记录静默跳过 ——
 * 使用记录是锦上添花，绝不能让直达流程因此失败。
 */
object CampusDeviceHubRepositoryHolder {

    @Volatile
    private var repository: CampusDeviceHubRepository? = null

    fun init(repository: CampusDeviceHubRepository) {
        this.repository = repository
    }

    /** 进程级单例（由 `CampusDeviceHubRepositoryHolder.init` 注入）；未初始化时返回 null（记录静默跳过）。 */
    fun get(context: Context): CampusDeviceHubRepository? = repository
}
