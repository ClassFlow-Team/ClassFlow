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
 * 账单详情弹层的状态。
 *
 * [order] 是列表里那一条（点开就能立刻显示机器、时间、状态），
 * [detail] 是后到的详情接口结果（带金额）。两者分开存，是为了让弹层先出内容
 * 再补金额，而不是点一下先白屏等网络。
 */
data class WasherHistoryDetailTarget(
    val order: WasherOrderSummary,
    val detail: WasherOrderSummary? = null,
    val loading: Boolean = false,
    @StringRes val errorRes: Int? = null,
) {
    /** 显示用：详情回来了就用详情，否则先用列表那一条。 */
    val shown: WasherOrderSummary get() = detail ?: order
}

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

        /** 账单里点开的那一条（null = 没有打开详情）。 */
        val historyDetail: WasherHistoryDetailTarget? = null,
    ) {
        /** 页面主卡片要显示的那一单。 */
        val order: WasherOrderSummary? get() = snapshot?.order

        /** 有没有正在进行的单（决定要不要显示倒计时）。 */
        val hasActiveOrder: Boolean get() = order?.phase?.isActive == true

        /** 本月洗衣次数与金额（账单顶部小计）。 */
        val monthSpent: Double
            get() = history.filter { it.phase.isTerminal && it.hasKnownPrice }.sumOf { it.payPrice }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    private var started = false

    /**
     * 「正在刷新」的**同步**标记。
     *
     * 不能只看 `_uiState.refreshing`：它是在协程**内部**才置位的，而协程启动是异步的。
     * 页面一进来 `start()` 会触发一次刷新，用户又点了刷新按钮 / 下拉，几次调用都会
     * 在状态还没来得及更新时通过检查，于是并发打十几次详情接口（实测 1 秒内 143 次）。
     *
     * 这里在主线程直接做「比较并置位」，同一时刻只允许一个刷新在跑。
     */
    private var refreshInFlight = false

    /** 账单加载的同步防重入标记（理由同上）。 */
    private var historyInFlight = false

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
        // 同步的「比较并置位」：并发调用只有第一个能进来（见 refreshInFlight）
        if (refreshInFlight) return
        refreshInFlight = true
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true, errorRes = null) }
            try {
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
            } finally {
                // 失败 / 取消都要放开，否则一次异常会让页面永远卡在「刷新中」
                refreshInFlight = false
            }
        }
    }

    /** 加载洗衣账单（历史订单）。[reset] 为 true 时从第一页重来。 */
    fun loadHistory(reset: Boolean = false) {
        val state = _uiState.value
        // 同 refresh：historyLoading 是协程内才置位的，必须用同步标记挡住并发
        if (historyInFlight) return
        val nextPage = if (reset) 1 else state.historyPage + 1
        if (!reset && !state.historyHasMore) return
        // 首次加载失败后用户再点「加载更多」应该重试第一页，而不是永远卡在 hasMore=false
        val pageToLoad = if (!reset && state.historyPage == 0) 1 else nextPage
        historyInFlight = true

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    historyLoading = true,
                    historyErrorRes = null,
                    history = if (reset) emptyList() else it.history,
                )
            }
            try {
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
            } finally {
                historyInFlight = false
            }
        }
    }

    /**
     * 打开一条账单的详情（含金额）。
     *
     * `orders/history` 的列表项**没有金额字段**（实测只给 `payFlag`），金额只在
     * `orders/{id}/detail` 里 —— 所以详情是按需查的，不预取整个列表：
     * 账单一屏 20 条，进页面就打 20 个详情请求既慢又没必要。
     */
    fun openHistoryDetail(order: WasherOrderSummary) {
        _uiState.update {
            it.copy(
                historyDetail = WasherHistoryDetailTarget(
                    order = order,
                    detail = null,
                    loading = true,
                    errorRes = null,
                )
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    WasherOrderSession.withClient(context) {
                        it.fetchWasherOrderDetail(order.orderId)
                    }
                }
            }
            val detail = result.getOrNull()
            _uiState.update { current ->
                val target = current.historyDetail ?: return@update current
                // 用户可能已经关掉或换了一条：只更新仍然匹配的那次请求
                if (target.order.orderId != order.orderId) return@update current
                if (detail == null) {
                    current.copy(
                        historyDetail = target.copy(
                            loading = false,
                            errorRes = R.string.washer_bill_detail_error,
                        )
                    )
                } else {
                    // 详情比列表新：把列表里那一条也换成详情，避免两处字段打架
                    current.copy(
                        historyDetail = target.copy(detail = detail, loading = false),
                        history = current.history.map {
                            if (it.orderId == order.orderId) detail else it
                        },
                    )
                }
            }
        }
    }

    fun closeHistoryDetail() {
        _uiState.update { it.copy(historyDetail = null) }
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

    /** 用户刚授予精确闹钟权限：把当前这一单的提醒重排成真正的系统闹钟。 */
    fun rescheduleForExactAlarm() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { watcher.scheduleFromCurrentSnapshot() }
                    .onFailure { Log.w(TAG, "重排洗衣闹钟失败", it) }
            }
        }
    }

    /** 「立即检查一次」：提醒设置里的手动验证入口。 */
    fun checkNow() {        viewModelScope.launch {
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
