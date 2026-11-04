package com.shiro.classflow.ui.campus.device

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.xingheyuzhuan.shiguangschedule.R
import com.shiro.classflow.data.model.wbu.CampusDeviceEntry
import com.shiro.classflow.data.model.wbu.CampusDeviceKind
import com.shiro.classflow.data.network.wbu.WbuCampusCardClient
import com.shiro.classflow.data.network.wbu.WbuUjingClient
import com.xingheyuzhuan.shiguangschedule.data.repository.CampusDeviceHubRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    fun notify(@StringRes messageRes: Int) {
        _messages.tryEmit(messageRes)
    }
}
