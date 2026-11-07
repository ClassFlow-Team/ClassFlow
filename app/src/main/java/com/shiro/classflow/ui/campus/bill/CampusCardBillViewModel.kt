package com.shiro.classflow.ui.campus.bill

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shiro.classflow.data.model.wbu.CredentialService
import com.shiro.classflow.data.model.wbu.LegacyBillCategory
import com.shiro.classflow.data.model.wbu.LegacyCampusCardEntry
import com.shiro.classflow.data.model.wbu.LegacyCampusCardMode
import com.shiro.classflow.data.model.wbu.LegacyYearTotals
import com.shiro.classflow.data.network.wbu.AccessFailure
import com.shiro.classflow.data.network.wbu.WbuLegacyCampusCardClient
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.ui.components.CampusAccessResult
import com.xingheyuzhuan.shiguangschedule.ui.components.accessFailureText
import com.xingheyuzhuan.shiguangschedule.ui.components.campusAccess
import com.xingheyuzhuan.shiguangschedule.ui.components.shouldOfferDirectOnce
import com.xingheyuzhuan.shiguangschedule.ui.components.shouldOfferWebVpnOnce
import java.time.YearMonth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 月账单弹层状态。 */
data class MonthSheetState(
    /** `yyyy-MM`。 */
    val month: String,
    val isLoading: Boolean = true,
    val categories: List<LegacyBillCategory> = emptyList(),
    val selectedCategory: LegacyBillCategory? = null,
    val categoryRows: List<LegacyCampusCardEntry> = emptyList(),
    val isCategoryLoading: Boolean = false,
    val yearTotals: LegacyYearTotals? = null,
    /** 年度趋势正在展示的年份；初始为该月所属年份，可在弹层里独立切换。 */
    val year: Int = month.take(4).toIntOrNull() ?: YearMonth.now().year,
    val isYearLoading: Boolean = false,
    /** 年度趋势单独的错误，不影响上面的分类与明细。 */
    val yearError: String? = null,
    val errorMessage: String? = null
) {
    /** 本月总支出 = 所有分类金额之和。 */
    val totalExpense: Double get() = categories.sumOf { it.amount }
}

/** 一卡通账单页状态。 */
data class CampusCardBillUiState(
    val mode: LegacyCampusCardMode = LegacyCampusCardMode.BILL,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val entries: List<LegacyCampusCardEntry> = emptyList(),
    val total: Int = 0,
    val page: Int = 0,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
    val needLogin: Boolean = false,
    val selectedEntry: LegacyCampusCardEntry? = null,
    val monthSheet: MonthSheetState? = null,
    /** 最近一笔交易后的余额。 */
    val latestBalance: Double? = null,
    /** 本月总支出；没取到时为 null（不显示摘要）。 */
    val monthExpense: Double? = null,
    /**
     * 是否至少拿到过一次确定的结果（成功或失败都算「试过了」）。
     *
     * 存在的唯一目的：把「加载成功但确实没有账单」和「根本没拿到结果（用户取消了输入）」分开。
     * 缺了它，取消一次 WebVPN 验证码就会被渲染成「本月暂无账单」—— 一句凭空编造的结论。
     */
    val hasLoadedOnce: Boolean = false,
    /** 本次是直连失败、且不是 WebVPN 模式 → 页面给一个「改用 WebVPN」按钮。 */
    val offerWebVpnOnce: Boolean = false,
    /** 本次是 WebVPN 失败、且当前是 WebVPN 模式 → 页面给一个「改用直连」按钮。 */
    val offerDirectOnce: Boolean = false,
    /** 用户取消了输入（WebVPN 验证码 / 密码 / 校园网确认），本次没有拿到任何结果。 */
    val cancelled: Boolean = false
) {
    /** 加载成功、确实没有数据。**只有这里**才允许显示「本月暂无账单」。 */
    val isEmpty: Boolean
        get() = hasLoadedOnce && !isLoading && !isRefreshing && errorMessage == null && entries.isEmpty()

    /** 取消且没有任何可展示的数据：中性占位 + 重试，绝不冒充「没有账单」。 */
    val cancelledWithoutData: Boolean
        get() = cancelled && !isLoading && !isRefreshing && errorMessage == null && entries.isEmpty()
}

/**
 * 一卡通账单页逻辑：旧平台原生账单 / 流水 / 月账单 / 详情。
 *
 * 登录失败的处理与付款码页一致：旧平台会话失效 → 重建一次；CASTGC 失效 → 用保存的凭据
 * 静默重登一次；仍失败才把 [CampusCardBillUiState.needLogin] 交给页面弹登录 Sheet。
 */
class CampusCardBillViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val FLOW_TAG = "CARD_BILL"
    }

    /**
     * 旧平台在校园网内直连、校外走 WebVPN，两条通道各一个客户端（共享 Cookie 罐）。
     * 通道由 [campusAccess] 按「自动校园网探测」决定。
     */
    private val directClient = WbuLegacyCampusCardClient(application, useVpn = false)
    private val vpnClient = WbuLegacyCampusCardClient(application, useVpn = true)

    private fun client(useVpn: Boolean): WbuLegacyCampusCardClient =
        if (useVpn) vpnClient else directClient

    private val _uiState = MutableStateFlow(CampusCardBillUiState())
    val uiState: StateFlow<CampusCardBillUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var summaryJob: Job? = null
    private var monthJob: Job? = null
    private var categoryJob: Job? = null
    private var yearJob: Job? = null
    private var started = false

    /** 单次改用 WebVPN：只对下一次访问生效，**不写全局开关**。 */
    private var useWebVpnOnce = false

    /** 单次改回直连：同上，是 [useWebVpnOnce] 的对称动作。 */
    private var useDirectOnce = false

    fun start() {
        if (started) return
        started = true
        load(isRefresh = false)
    }

    fun refresh() {
        val state = _uiState.value
        if (state.isLoading || state.isRefreshing) return
        load(isRefresh = true)
    }

    /**
     * 「改用 WebVPN」按钮：本次访问改走校外通道（单次生效，不翻转全局开关）。
     *
     * 有旧数据时按「刷新」处理，让列表留在原地而不是闪成整页 loading。
     */
    fun retryWithWebVpnOnce() {
        useWebVpnOnce = true
        useDirectOnce = false
        load(isRefresh = _uiState.value.entries.isNotEmpty())
    }

    /**
     * 「改用直连」按钮：本次不走 WebVPN（单次生效，不翻转全局开关）。
     *
     * WebVPN 连不上时用 —— 最常见的情形是人已经走回校园网、或校园 VPN 刚恢复。
     */
    fun retryWithDirectOnce() {
        useDirectOnce = true
        useWebVpnOnce = false
        load(isRefresh = _uiState.value.entries.isNotEmpty())
    }

    fun switchMode(mode: LegacyCampusCardMode) {
        if (mode == _uiState.value.mode) return
        loadJob?.cancel()
        summaryJob?.cancel()
        monthJob?.cancel()
        categoryJob?.cancel()
        _uiState.update {
            it.copy(
                mode = mode,
                entries = emptyList(),
                total = 0,
                page = 0,
                hasMore = false,
                latestBalance = null,
                monthExpense = null,
                errorMessage = null,
                needLogin = false,
                selectedEntry = null,
                monthSheet = null,
                hasLoadedOnce = false,
                offerWebVpnOnce = false,
                offerDirectOnce = false,
                cancelled = false
            )
        }
        load(isRefresh = false)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isRefreshing || state.isLoadingMore || !state.hasMore) return
        val mode = state.mode
        val nextPage = state.page + 1
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            when (val result = withAccess { useVpn -> fetchPage(mode, nextPage, useVpn) }) {
                is RequestResult.Ok -> {
                    if (_uiState.value.mode != mode) return@launch
                    val page = result.value
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            entries = it.entries + page.rows,
                            total = page.total,
                            page = nextPage,
                            hasMore = page.hasMore
                        )
                    }
                }

                is RequestResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            errorMessage = errorText(result.failure, R.string.bill_error_load)
                        )
                    }
                }

                is RequestResult.NeedLogin -> {
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            needLogin = true,
                            errorMessage = errorText(result.failure, R.string.err_need_unified_auth_session)
                        )
                    }
                }

                is RequestResult.Cancelled -> _uiState.update { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun openDetail(entry: LegacyCampusCardEntry) {
        _uiState.update { it.copy(selectedEntry = entry) }
    }

    fun closeDetail() {
        _uiState.update { it.copy(selectedEntry = null) }
    }

    /** 打开某个月的月账单。 */
    fun openMonth(month: String) {
        monthJob?.cancel()
        categoryJob?.cancel()
        yearJob?.cancel()
        val year = month.take(4).toIntOrNull() ?: YearMonth.now().year
        _uiState.update { it.copy(monthSheet = MonthSheetState(month = month, year = year)) }

        monthJob = viewModelScope.launch {
            when (val result = withAccess { useVpn -> client(useVpn).fetchMonthCategories(month) }) {
                is RequestResult.Ok -> {
                    updateMonthSheet { it.copy(isLoading = false, categories = result.value) }
                    loadYearTotals(year)
                }

                is RequestResult.Error -> updateMonthSheet {
                    it.copy(
                        isLoading = false,
                        errorMessage = errorText(result.failure, R.string.bill_error_load)
                    )
                }

                is RequestResult.NeedLogin -> {
                    _uiState.update {
                        it.copy(
                            monthSheet = null,
                            needLogin = true,
                            errorMessage = errorText(result.failure, R.string.err_need_unified_auth_session)
                        )
                    }
                }

                is RequestResult.Cancelled -> updateMonthSheet { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * 切换「年度趋势」展示的年份。
     *
     * 只重载条形图，**不动**上面的分类与分类明细 —— 那些绑定的是 [MonthSheetState.month]。
     * 快速连点会取消上一次请求，避免旧年份的结果覆盖新年份。
     */
    fun selectYear(year: Int) {
        val sheet = _uiState.value.monthSheet ?: return
        if (sheet.year == year && sheet.yearTotals != null && sheet.yearError == null) return
        if (year !in legacyYearRange()) return

        yearJob?.cancel()
        updateMonthSheet { it.copy(year = year, isYearLoading = true, yearError = null) }
        loadYearTotals(year)
    }

    fun selectCategory(category: LegacyBillCategory) {
        val sheet = _uiState.value.monthSheet ?: return
        if (sheet.selectedCategory?.code == category.code && sheet.categoryRows.isNotEmpty()) return

        categoryJob?.cancel()
        updateMonthSheet {
            it.copy(
                selectedCategory = category,
                categoryRows = emptyList(),
                isCategoryLoading = true,
                errorMessage = null
            )
        }

        categoryJob = viewModelScope.launch {
            val month = sheet.month
            when (val result = withAccess { useVpn -> client(useVpn).fetchMonthRows(month, category.code) }) {
                is RequestResult.Ok -> updateMonthSheet {
                    it.copy(isCategoryLoading = false, categoryRows = result.value.rows)
                }

                is RequestResult.Error -> updateMonthSheet {
                    it.copy(
                        isCategoryLoading = false,
                        errorMessage = errorText(result.failure, R.string.bill_error_load)
                    )
                }

                is RequestResult.NeedLogin -> {
                    _uiState.update {
                        it.copy(
                            monthSheet = null,
                            needLogin = true,
                            errorMessage = errorText(result.failure, R.string.err_need_unified_auth_session)
                        )
                    }
                }

                is RequestResult.Cancelled -> updateMonthSheet { it.copy(isCategoryLoading = false) }
            }
        }
    }

    fun closeMonthSheet() {
        monthJob?.cancel()
        categoryJob?.cancel()
        yearJob?.cancel()
        _uiState.update { it.copy(monthSheet = null) }
    }

    fun onLoginSuccess() {
        _uiState.update { it.copy(needLogin = false) }
        directClient.forgetSession()
        vpnClient.forgetSession()
        load(isRefresh = true)
    }

    fun onLoginDismissed() {
        _uiState.update { it.copy(needLogin = false) }
    }

    // ------------------- 主加载 -------------------

    private fun load(isRefresh: Boolean) {
        loadJob?.cancel()
        summaryJob?.cancel()
        val mode = _uiState.value.mode
        val once = useWebVpnOnce
        val directOnce = useDirectOnce
        useWebVpnOnce = false
        useDirectOnce = false

        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = !isRefresh && it.entries.isEmpty(),
                    isRefreshing = isRefresh,
                    errorMessage = null,
                    needLogin = false,
                    offerWebVpnOnce = false,
                    offerDirectOnce = false,
                    cancelled = false
                )
            }

            when (
                val result = withAccess(
                    useWebVpnOnce = once,
                    useDirectOnce = directOnce
                ) { useVpn -> fetchPage(mode, 1, useVpn) }
            ) {
                is RequestResult.Ok -> {
                    val page = result.value
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            entries = page.rows,
                            total = page.total,
                            page = 1,
                            hasMore = page.hasMore,
                            latestBalance = page.rows.firstOrNull()?.cardBalance,
                            hasLoadedOnce = true,
                            offerWebVpnOnce = false,
                            offerDirectOnce = false,
                            cancelled = false
                        )
                    }
                    if (mode == LegacyCampusCardMode.BILL) {
                        loadMonthExpense()
                    }
                }

                is RequestResult.Error -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = errorText(result.failure, R.string.bill_error_load),
                        // 直连不通 → 「改用 WebVPN」；WebVPN 不通 → 「改用直连」。
                        // 两者只对本次生效，不翻转全局开关。
                        offerWebVpnOnce = result.failure.shouldOfferWebVpnOnce(result.useVpn),
                        offerDirectOnce = result.failure.shouldOfferDirectOnce(result.useVpn),
                        cancelled = false
                    )
                }

                is RequestResult.NeedLogin -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        needLogin = true,
                        errorMessage = errorText(result.failure, R.string.err_need_unified_auth_session),
                        // 登录态问题有「重新登录」这条更对的路，不再叠通道按钮
                        offerWebVpnOnce = false,
                        offerDirectOnce = false,
                        cancelled = false
                    )
                }

                // 用户取消了输入（多半是 WebVPN 验证码）：
                // 有旧数据就保留，只在列表底部标一行「已取消刷新」；
                // 没有数据就进中性占位 —— 绝不能渲染成「本月暂无账单」。
                is RequestResult.Cancelled -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        cancelled = true,
                        offerWebVpnOnce = false,
                        offerDirectOnce = false
                    )
                }
            }
        }
    }

    private suspend fun fetchPage(mode: LegacyCampusCardMode, page: Int, useVpn: Boolean) =
        if (mode == LegacyCampusCardMode.BILL) {
            client(useVpn).fetchBill(page)
        } else {
            client(useVpn).fetchFlow(page)
        }

    private fun loadMonthExpense() {
        val month = YearMonth.now().toString()
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            when (val result = withAccess { useVpn -> client(useVpn).fetchMonthCategories(month) }) {
                is RequestResult.Ok -> _uiState.update {
                    it.copy(monthExpense = result.value.sumOf { category -> category.amount })
                }

                // 摘要只是锦上添花，失败不打断列表
                else -> Unit
            }
        }
    }

    /**
     * 年度月度汇总。
     *
     * 独立 job：切换年份时能取消上一次；失败只写 [MonthSheetState.yearError]，
     * 不污染整个弹层（分类和明细此时还是好的）。
     */
    private fun loadYearTotals(year: Int) {
        yearJob?.cancel()
        yearJob = viewModelScope.launch {
            updateMonthSheet { it.copy(isYearLoading = true, yearError = null) }
            when (val result = withAccess { useVpn -> client(useVpn).fetchYearTotals(year) }) {
                is RequestResult.Ok -> updateMonthSheet {
                    // 结果回来时年份可能已被切走，丢弃过期响应
                    if (it.year != year) it
                    else it.copy(yearTotals = result.value, isYearLoading = false, yearError = null)
                }

                is RequestResult.Error -> updateMonthSheet {
                    if (it.year != year) it
                    else it.copy(
                        isYearLoading = false,
                        yearError = errorText(result.failure, R.string.bill_error_load)
                    )
                }

                else -> updateMonthSheet {
                    if (it.year != year) it else it.copy(isYearLoading = false)
                }
            }
        }
    }

    // ------------------- 登录 / 错误 -------------------

    private sealed interface RequestResult<out T> {
        data class Ok<T>(val value: T, val useVpn: Boolean) : RequestResult<T>
        data class Error(val failure: AccessFailure, val useVpn: Boolean) : RequestResult<Nothing>
        data class NeedLogin(val failure: AccessFailure?, val useVpn: Boolean) : RequestResult<Nothing>
        data class Cancelled(val useVpn: Boolean) : RequestResult<Nothing>
    }

    /**
     * 统一走 `campusAccess`：决定直连 / WebVPN、静默重建统一认证、直连失败后重算通道、
     * 会话失效后重试一次 —— 与成绩 / 图书馆 / 选课等校园服务同一套口径。
     *
     * 旧一卡通平台 `yktyd.wbu.edu.cn:81` 只在校内可达，所以这里**必须**做校园网探测
     * （`WbuCampusChannel` 旧注释把一卡通排除在外是错的）。
     *
     * @param useWebVpnOnce 用户点了「改用 WebVPN」：本次强制走校外通道。
     */
    private suspend fun <T> withAccess(
        useWebVpnOnce: Boolean = false,
        useDirectOnce: Boolean = false,
        block: suspend (useVpn: Boolean) -> T
    ): RequestResult<T> {
        return when (
            val result = campusAccess(
                context = getApplication<Application>(),
                service = CredentialService.UNIFIED_AUTH,
                flowTag = FLOW_TAG,
                useWebVpnOnce = useWebVpnOnce,
                useDirectOnce = useDirectOnce,
                block = block
            )
        ) {
            is CampusAccessResult.Ok -> RequestResult.Ok(result.value, result.useVpn)

            is CampusAccessResult.Failed -> when (val failure = result.failure) {
                is AccessFailure.SessionExpired,
                is AccessFailure.CredentialRejected ->
                    RequestResult.NeedLogin(failure, result.useVpn)

                is AccessFailure.Cancelled -> RequestResult.Cancelled(result.useVpn)
                else -> RequestResult.Error(failure, result.useVpn)
            }
        }
    }

    private fun errorText(failure: AccessFailure?, fallbackRes: Int): String? {
        val app = getApplication<Application>()
        if (failure == null) return app.getString(R.string.err_need_unified_auth_session)
        return accessFailureText(app, failure)
            ?: if (failure is AccessFailure.Cancelled) null else app.getString(fallbackRes)
    }

    private fun updateMonthSheet(transform: (MonthSheetState) -> MonthSheetState) {
        _uiState.update { state ->
            val sheet = state.monthSheet ?: return@update state
            state.copy(monthSheet = transform(sheet))
        }
    }

}
