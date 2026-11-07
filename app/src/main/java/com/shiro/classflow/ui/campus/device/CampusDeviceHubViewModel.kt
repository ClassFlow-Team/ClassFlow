package com.shiro.classflow.ui.campus.device

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.R
import com.shiro.classflow.data.model.wbu.CampusDeviceEntry
import com.shiro.classflow.data.model.wbu.CampusDeviceKind
import com.shiro.classflow.data.model.wbu.WebAppId
import com.shiro.classflow.data.network.wbu.CampusShowerEntryResolver
import com.shiro.classflow.data.network.wbu.CampusShowerLink
import com.shiro.classflow.data.network.wbu.ShowerManualCode
import com.shiro.classflow.data.network.wbu.WbuAuthTransport
import com.shiro.classflow.data.network.wbu.WbuCampusCardClient
import com.shiro.classflow.data.network.wbu.WbuUjingClient
import com.xingheyuzhuan.shiguangschedule.data.repository.CampusDeviceHubRepository
import com.xingheyuzhuan.shiguangschedule.ui.components.shouldAttemptSavedPasswordLogin
import com.xingheyuzhuan.shiguangschedule.ui.components.silentUnifiedAuthLogin
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 校园设备管理页（饮水机 / 洗衣机 / 洗浴）的逻辑：使用记录、重命名、桌面快捷方式。
 *
 * 页面本身不碰任何接口 —— 记录是在设备**真正用上**的那一刻由各业务页写入的
 * （取水下单成功 / 洗衣机拿到直达地址 / 洗浴解析成功），这里只读、只改。
 */
@HiltViewModel
class CampusDeviceHubViewModel @Inject constructor(
    private val repository: CampusDeviceHubRepository,
    private val resolver: CampusShowerEntryResolver,
    // 存量补名要打接口：这里注入 context，避免把 WbuUjingClient 的构造细节散到页面层
    @ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private val TAG = "CampusDeviceHubVM"

    /** 存量补名只跑一次（重组 / 重进页面不重复打接口）。 */
    private var backfillStarted = false

    data class UiState(
        val entries: List<CampusDeviceEntry> = emptyList(),
    )

    private val kindFlow = MutableStateFlow(CampusDeviceKind.WATER)

    val uiState: StateFlow<UiState> = combine(
        kindFlow,
        repository.entries,
    ) { kind, entries ->
        UiState(entries = entries.filter { it.kind == kind })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    /** 页面管理的设备类型（导航参数）。 */
    val kind: CampusDeviceKind
        get() = kindFlow.value

    /** 由页面在进入时调用一次；重复调用（重组 / 配置变更）无副作用。 */
    fun start(kindName: String) {
        kindFlow.value = CampusDeviceKind.fromNameOrNull(kindName) ?: CampusDeviceKind.WATER
        backfillWasherNames()
    }

    /**
     * 存量洗衣机记录的自愈：**老版本把 uuid 尾号当机号写过**（`deviceNo=40004678`），
     * 那种记录光靠「下次扫码补名」永远修不好 —— 用户不一定会再扫一次。
     *
     * 这里在打开洗衣机页时补一次：拿 raw 里的 uuid 重新走一遍扫码链路取 deviceId，
     * 再查详情接口拿真机号。失败（未登录 / 设备不存在）静默跳过，不影响页面。
     */
    private fun backfillWasherNames() {
        if (backfillStarted) return
        backfillStarted = true
        viewModelScope.launch {
            val entries = repository.entriesOf(CampusDeviceKind.WASHER).first()
            val needsFix = entries.filter {
                it.deviceNo.isNullOrBlank() ||
                    CampusDeviceHubRepository.isPlaceholderDeviceNo(it.key, it.deviceNo)
            }
            if (needsFix.isEmpty()) return@launch
            Log.i(TAG, "补名存量洗衣机记录 ${needsFix.size} 条")
            needsFix.forEach { entry ->
                val raw = entry.raw.ifBlank {
                    "http://app.littleswan.com/u_download.html?type=Ujing&uuid=${entry.key}"
                }
                runCatching {
                    val cardClient = WbuCampusCardClient(context, useVpn = false)
                    val token = cardClient.ensureValidAccessToken()
                    val client = WbuUjingClient(context, cardClient)
                    client.connect(token, appId = WbuUjingClient.WASHER_APP_ID)
                    val scan = client.scanWasherCode(raw)
                    val deviceId = scan.lastUseDeviceId ?: return@forEach
                    val info = client.washerDeviceInfo(deviceId) ?: return@forEach
                    repository.fillNames(
                        CampusDeviceKind.WASHER,
                        key = entry.key,
                        storeName = info.storeName,
                        subjectName = info.serviceSubjectName,
                        deviceNo = info.deviceNo,
                    )
                    Log.i(TAG, "存量补名完成: ${info.storeName} / ${info.deviceNo}")
                }.onFailure { Log.w(TAG, "存量补名失败: ${entry.key}", it) }
            }
        }
    }

    fun rename(key: String, alias: String) {
        viewModelScope.launch { repository.rename(kindFlow.value, key, alias) }
    }

    fun setIcon(key: String, iconId: String) {
        viewModelScope.launch { repository.setIcon(kindFlow.value, key, iconId) }
    }

    fun delete(key: String) {
        viewModelScope.launch { repository.delete(kindFlow.value, key) }
    }

    /**
     * 改一台洗浴设备的类型（1 栋 ⇄ 2-3 栋）。
     *
     * 类型是设备身份的一部分（写在 key 里），所以这里走仓库的「换 key」逻辑；
     * [target] 已由对话框用 [ShowerManualCode.normalize] 校验过。
     */
    fun changeShowerSystem(oldKey: String, target: ShowerManualCode.Candidate) {
        viewModelScope.launch {
            val updated = repository.changeShowerSystem(oldKey, target) ?: return@launch
            // 桌面快捷方式的 ID 跟着 key 走，换类型后必须把图标里的 Intent 也改掉，
            // 否则点桌面图标还是会按旧系统解析（见 updateAfterSystemChange 的注释）
            CampusDeviceShortcuts.updateAfterSystemChange(
                context = context,
                oldEntry = CampusDeviceEntry(
                    kind = CampusDeviceKind.SHOWER,
                    key = oldKey,
                    system = oldKey.substringBefore(':'),
                ),
                newEntry = updated,
            )
            notify(R.string.device_message_system_changed)
        }
    }

    fun notify(@StringRes messageRes: Int) {
        _messages.tryEmit(messageRes)
    }

    // ───────────────────────── 手动输入洗浴设备码 ─────────────────────────

    /**
     * 手动输入的解析状态。
     *
     * 为什么解析放在这个页面而不是 `ShowerDirect` 落地页：手动输入是**用户已经在 App 里**的操作，
     * 不需要品牌全屏过场。以前点确定会先跳到落地页（一大片蓝底），解析完再切到目标页，
     * 两次画面切换都很突兀；现在弹窗里原地转圈，成功才一次性导航。
     */
    data class ManualShowerUiState(
        val resolving: Boolean = false,
        @StringRes val errorRes: Int? = null,
    )

    private val _manualShower = MutableStateFlow(ManualShowerUiState())
    val manualShower: StateFlow<ManualShowerUiState> = _manualShower.asStateFlow()

    /** 解析成功后的目标页（一次性事件）。 */
    private val _openShower = MutableSharedFlow<Destination>(extraBufferCapacity = 1)
    val openShower: SharedFlow<Destination> = _openShower.asSharedFlow()

    /** 用户重新打开手动输入时清掉上一次的失败提示。 */
    fun resetManualShower() {
        _manualShower.value = ManualShowerUiState()
    }

    /**
     * 解析手输设备码并给出目标页；系统由用户在弹窗里勾选（[system]）。
     *
     * 与 [ShowerDirectViewModel] 共用 [CampusShowerEntryResolver.resolveManual] 与同一套
     * 「登录态失效最多静默重登一次」的重试规则，只是不再经过过渡页。
     */
    fun resolveManualShower(system: String, code: String, port: String?) {
        if (_manualShower.value.resolving) return
        viewModelScope.launch {
            _manualShower.value = ManualShowerUiState(resolving = true)
            var autoLoginTried = false
            while (true) {
                val result = resolver.resolveManual(system, code, port)

                if (result == CampusShowerEntryResolver.Result.NeedLogin &&
                    !autoLoginTried &&
                    shouldAttemptSavedPasswordLogin(context)
                ) {
                    autoLoginTried = true
                    val failure = silentUnifiedAuthLogin(
                        context = context,
                        flowTag = "SHOWER_MANUAL",
                        viaWebVpn = WbuAuthTransport.getIdsViaWebVpn(context),
                        onlyWithSavedPassword = true
                    )
                    if (failure == null) continue
                    _manualShower.value = ManualShowerUiState(
                        errorRes = R.string.err_need_unified_auth_session
                    )
                    return@launch
                }

                when (result) {
                    is CampusShowerEntryResolver.Result.Ready -> {
                        _manualShower.value = ManualShowerUiState()
                        recordShowerUse(result)
                        _openShower.tryEmit(result.toDestination())
                    }

                    CampusShowerEntryResolver.Result.InvalidDevice -> {
                        Log.w(TAG, "手输洗浴失败：设备无效 system=$system code=$code")
                        _manualShower.value = ManualShowerUiState(
                            errorRes = R.string.link_hub_error_shower_invalid
                        )
                    }

                    CampusShowerEntryResolver.Result.NeedLogin -> {
                        Log.w(TAG, "手输洗浴失败：需要登录 system=$system")
                        _manualShower.value = ManualShowerUiState(
                            errorRes = R.string.err_need_unified_auth_session
                        )
                    }

                    is CampusShowerEntryResolver.Result.Unavailable -> {
                        Log.w(TAG, "手输洗浴失败 system=$system code=$code detail=${result.message}")
                        _manualShower.value = ManualShowerUiState(
                            errorRes = R.string.link_hub_error_shower_unavailable
                        )
                    }
                }
                return@launch
            }
        }
    }

    /**
     * 记一次洗浴使用。2-3 栋原生淋浴页（`ShowerWaterViewModel`）自己会记 ——
     * `nativeShower` 非空时正是那一跳，这里跳过以免同一次使用记两遍。
     */
    private fun recordShowerUse(ready: CampusShowerEntryResolver.Result.Ready) {
        if (ready.nativeShower != null) return
        viewModelScope.launch {
            runCatching {
                repository.record(
                    CampusDeviceEntry(
                        kind = CampusDeviceKind.SHOWER,
                        key = CampusDeviceHubRepository.showerKey(
                            ready.resolvedSystem ?: CampusShowerLink.SYSTEM_YKT,
                            ready.resolvedCode.orEmpty(),
                            ready.resolvedPort
                        ),
                        raw = ready.initialUrl,
                        system = ready.resolvedSystem,
                        port = ready.resolvedPort,
                        storeName = ready.storeName,
                        deviceNo = ready.resolvedCode,
                    )
                )
            }.onFailure { Log.w(TAG, "记录洗浴使用失败", it) }
        }
    }
}

/**
 * 解析结果 → 目标页。与 `ShowerDirectScreen` 里那两跳完全等价，只是不再经过过渡页：
 * 已正证是淋浴（2-3 栋）→ 原生用水页；其它 → 一卡通网页容器。
 */
private fun CampusShowerEntryResolver.Result.Ready.toDestination(): Destination {
    val native = nativeShower
    return if (native != null) {
        Destination.ShowerWater(
            deviceId = native.deviceId,
            port = native.port,
            implid = native.implid,
            feeitemid = native.feeitemid,
            webFallbackUrl = initialUrl
        )
    } else {
        Destination.WebApp(
            appId = WebAppId.CAMPUS_CARD.name,
            initialTargetUrl = initialUrl,
            pendingAutoScan = pendingAutoScan
        )
    }
}
