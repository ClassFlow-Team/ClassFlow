package com.shiro.classflow.ui.campus.device

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.xingheyuzhuan.shiguangschedule.R
import com.shiro.classflow.data.model.wbu.WasherOrderSnapshot
import com.shiro.classflow.data.model.wbu.WasherOrderSummary
import com.shiro.classflow.data.network.wbu.WasherOrderSession
import com.xingheyuzhuan.shiguangschedule.data.repository.WasherOrderRepository
import com.xingheyuzhuan.shiguangschedule.service.WasherNotification
import com.xingheyuzhuan.shiguangschedule.service.WasherWatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 洗衣机页的「进度 + 账单 + 提醒」逻辑。
 *
 * 与 [CampusDeviceHubViewModel] 分开：那份管三类设备共用的「使用记录」，
 * 这份只管洗衣机独有的订单状态，页面按 kind 决定挂不挂它。
 */
@HiltViewModel
class WasherProgressViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orderRepository: WasherOrderRepository,
    private val watcher: WasherWatcher,
) : ViewModel() {

    private companion object {
        const val TAG = "WasherProgressVM"

        /** 账单每页条数：与 H5 一致。 */
        const val HISTORY_PAGE_SIZE = 20
    }

    data class UiState(
        /** 进行中 / 刚结束的那一单（含快照时间，倒计时接着走）。 */
        val snapshot: WasherOrderSnapshot? = null,
        val refreshing: Boolean = false,
        /** 首次加载还没回来（显示骨架而不是空状态）。 */
        val loading: Boolean = true,
        /** 查询失败的原因（页面顶部提示，不清掉已有数据）。 */
        @StringRes val errorRes: Int? = null,

        /** 洗衣账单（历史订单）。 */
        val history: List<WasherOrderSummary> = emptyList(),
        val historyLoading: Boolean = false,
        val historyErrorRes: Int? = null,
        /** 还有没有下一页。 */
        val historyHasMore: Boolean = false,
        /** 账单页当前已加载到第几页（从 1 开始）。 */
        val historyPage: Int = 0,

        /** 提醒开关（默认关）。 */
        val reminderEnabled: Boolean = false,
        /** 后台自动查询（默认开）。 */
        val backgroundPolling: Boolean = true,
        /** 提醒设置面板是否展开。 */
        val settingsOpen: Boolean = false,
    ) {
        /** 页面主卡片要显示的那一单。 */
        val order: WasherOrderSummary? get() = snapshot?.order

        /** 有没有正在进行的单（决定要不要显示倒计时）。 */
        val hasActiveOrder: Boolean get() = order?.phase?.isActive == true

        /** 本月洗衣次数与金额（账单顶部小计）。 */
        val monthSpent: Double
            get() = history.filter { it.phase.isTerminal }.sumOf { it.payPrice }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    private var started = false

    /**
     * 页面进入 / 重新回到前台时调用。
     *
     * 每次回来都刷新一次：用户很可能刚在 H5 里下完单、或刚从洗衣机旁回来，
     * 那一刻正是最需要看到新状态的时机。本地快照只在第一次读，之后只覆盖不闪空。
     */
    fun start() {
        if (!started) {
            started = true
            viewModelScope.launch {
                val snapshot = orderRepository.currentSnapshot()
                val reminder = orderRepository.isReminderEnabled()
                val polling = orderRepository.isBackgroundPollingEnabled()
                _uiState.update {
                    it.copy(
                        snapshot = snapshot,
                        reminderEnabled = reminder,
                        backgroundPolling = polling,
                        loading = snapshot == null,
                    )
                }
                loadHistory(reset = true)
            }
        }
        refresh(silent = true)
    }

    /**
     * 刷新进行中订单。
     *
     * @param silent 进入页面时的首次刷新：失败不弹 Toast，只把错误放在顶部提示条里。
     */
    fun refresh(silent: Boolean = false) {
        if (_uiState.value.refreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true, errorRes = null) }
            val result = withContext(Dispatchers.IO) {
                // 用户主动刷新时不发通知：人就在页面上，弹通知是噪音
                watcher.refresh(discover = true, notify = false)
            }
            when (result) {
                is WasherWatcher.Result.Ok -> {
                    _uiState.update {
                        it.copy(
                            snapshot = result.snapshot,
                            refreshing = false,
                            loading = false,
                            errorRes = null,
                        )
                    }
                }

                WasherWatcher.Result.Idle -> {
                    _uiState.update {
                        it.copy(snapshot = null, refreshing = false, loading = false, errorRes = null)
                    }
                }

                is WasherWatcher.Result.Failed -> {
                    Log.w(TAG, "刷新洗衣进度失败", result.cause)
                    _uiState.update {
                        it.copy(
                            refreshing = false,
                            loading = false,
                            errorRes = R.string.washer_progress_error_refresh,
                        )
                    }
                    if (!silent) _messages.tryEmit(R.string.washer_progress_error_refresh)
                }
            }
            // 状态变了，后台调度跟着变（有单才轮询，没单就停）
            withContext(Dispatchers.IO) { watcher.syncBackgroundSchedule() }
        }
    }

    /** 加载洗衣账单（历史订单）。[reset] 为 true 时从第一页重来。 */
    fun loadHistory(reset: Boolean = false) {
        val state = _uiState.value
        if (state.historyLoading) return
        val nextPage = if (reset) 1 else state.historyPage + 1
        if (!reset && !state.historyHasMore) return
        // 首次加载失败后用户再点「加载更多」应该重试第一页，而不是永远卡在 hasMore=false
        val pageToLoad = if (!reset && state.historyPage == 0) 1 else nextPage

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    historyLoading = true,
                    historyErrorRes = null,
                    history = if (reset) emptyList() else it.history,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    WasherOrderSession.withClient(context) {
                        it.fetchWasherOrderHistory(page = pageToLoad, size = HISTORY_PAGE_SIZE)
                    }
                }
            }.onSuccess { page ->
                _uiState.update { current ->
                    val merged = if (reset) page else current.history + page
                    current.copy(
                        history = merged.distinctBy { it.orderId },
                        historyLoading = false,
                        historyPage = pageToLoad,
                        historyHasMore = page.size >= HISTORY_PAGE_SIZE,
                        historyErrorRes = null,
                    )
                }
            }.onFailure { e ->
                Log.w(TAG, "加载洗衣账单失败 page=$pageToLoad", e)
                _uiState.update {
                    it.copy(
                        historyLoading = false,
                        historyErrorRes = R.string.washer_bill_error,
                    )
                }
            }
        }
    }

    fun toggleSettings(open: Boolean = !_uiState.value.settingsOpen) {
        _uiState.update { it.copy(settingsOpen = open) }
    }

    /**
     * 完成提醒开关。
     *
     * 打开时顺手做两件事：把「后台查询」拉起来（否则提醒只是个摆设），
     * 以及清掉之前的去重标记 —— 用户重新打开提醒，说明愿意再听一次。
     */
    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            orderRepository.setReminderEnabled(enabled)
            _uiState.update { it.copy(reminderEnabled = enabled) }
            withContext(Dispatchers.IO) {
                if (enabled) {
                    // 立即排一次闹钟 / 轮询，不用等下一次进页面
                    watcher.refresh(discover = true, notify = false)
                } else {
                    watcher.stopWatching(clearSnapshot = false)
                }
                watcher.syncBackgroundSchedule()
            }
            if (enabled) {
                WasherNotification.ensureChannel(context)
                _messages.tryEmit(R.string.washer_settings_reminder_enabled_toast)
            } else {
                _messages.tryEmit(R.string.washer_settings_reminder_disabled_toast)
            }
        }
    }

    fun setBackgroundPollingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            orderRepository.setBackgroundPollingEnabled(enabled)
            _uiState.update { it.copy(backgroundPolling = enabled) }
            withContext(Dispatchers.IO) { watcher.syncBackgroundSchedule() }
        }
    }

    /** 「立即检查一次」：提醒设置里的手动验证入口。 */
    fun checkNow() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                watcher.refresh(discover = true, notify = true)
            }
            _messages.tryEmit(
                when (result) {
                    is WasherWatcher.Result.Ok -> R.string.washer_settings_check_ok
                    WasherWatcher.Result.Idle -> R.string.washer_settings_check_idle
                    is WasherWatcher.Result.Failed -> R.string.washer_progress_error_refresh
                }
            )
            if (result is WasherWatcher.Result.Ok) {
                _uiState.update { it.copy(snapshot = result.snapshot) }
            }
        }
    }

    /** 页面不可见时（切后台）重排一次闹钟，保证「关掉 App 也能响」。 */
    fun onScreenLeaving() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { watcher.scheduleFromCurrentSnapshot() }
        }
    }
}
