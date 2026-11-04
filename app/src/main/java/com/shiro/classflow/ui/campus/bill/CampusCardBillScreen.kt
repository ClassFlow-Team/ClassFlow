package com.shiro.classflow.ui.campus.bill

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.RiceBowl
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.shiro.classflow.data.model.wbu.LegacyBillCategory
import com.shiro.classflow.data.model.wbu.LegacyCampusCardEntry
import com.shiro.classflow.data.model.wbu.LegacyCampusCardMode
import com.shiro.classflow.data.model.wbu.LegacyYearTotals
import com.shiro.classflow.ui.campus.components.WbuAuthTipsScenario
import com.shiro.classflow.ui.campus.components.WbuCampusAuthSheet
import java.time.YearMonth

/** 收入绿 / 消费红，与旧平台详情弹窗的语义一致。 */
private val IncomeColor = Color(0xFF2E7D32)
private val ExpenseColor = Color(0xFFC62828)

/** U净 品牌紫 —— 原 SVG（`ujing_logo.xml`）的填充色，用于它的实心圆底。 */
private val UjingBrand = Color(0xFF4542CE)

/**
 * 一卡通账单（旧平台 · 完全原生）。
 *
 * 入口：设置 →「一卡通账单」。
 *
 * 数据来自旧平台自己的 AJAX 接口（`Report/GetMyBill` / `Report/GetPersonTrjn`），
 * 不是新平台，也不是 WebView：旧平台账单能拿到当天交易，详情字段更全。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusCardBillScreen(
    navBridge: NavBridge,
    viewModel: CampusCardBillViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showAuthSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.start() }

    LaunchedEffect(state.needLogin) {
        if (state.needLogin) showAuthSheet = true
    }

    if (showAuthSheet) {
        WbuCampusAuthSheet(
            onDismiss = {
                showAuthSheet = false
                viewModel.onLoginDismissed()
            },
            onLoginSuccess = {
                showAuthSheet = false
                viewModel.onLoginSuccess()
            },
            requireUnifiedCas = true,
            // 旧平台只需要统一认证会话（CASTGC），不涉及教务与校园网
            unifiedAuthOnly = true,
            title = stringResource(R.string.title_campus_card_bill),
            tipsScenario = WbuAuthTipsScenario.IDENTITY
        )
    }

    state.selectedEntry?.let { entry ->
        EntryDetailSheet(entry = entry, onDismiss = viewModel::closeDetail)
    }

    state.monthSheet?.let { sheet ->
        MonthBillSheet(
            state = sheet,
            onDismiss = viewModel::closeMonthSheet,
            onSelectCategory = viewModel::selectCategory,
            onSelectYear = viewModel::selectYear
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.title_campus_card_bill),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navBridge.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back)
                        )
                    }
                },
                actions = {
                    if (state.needLogin) {
                        IconButton(onClick = { showAuthSheet = true }) {
                            Icon(
                                Icons.Rounded.LockOpen,
                                contentDescription = stringResource(R.string.bill_action_relogin)
                            )
                        }
                    }
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !state.isLoading && !state.isRefreshing
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_refresh)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            BillModeTabs(
                mode = state.mode,
                onSelect = viewModel::switchMode
            )

            val pullRefreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                state = pullRefreshState,
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    state.isLoading -> LoadingBlock()

                    state.errorMessage != null && state.entries.isEmpty() -> ErrorBlock(
                        message = state.errorMessage.orEmpty(),
                        needLogin = state.needLogin,
                        offerWebVpnOnce = state.offerWebVpnOnce,
                        offerDirectOnce = state.offerDirectOnce,
                        onRetry = {
                            if (state.needLogin) showAuthSheet = true else viewModel.refresh()
                        },
                        onUseWebVpnOnce = viewModel::retryWithWebVpnOnce,
                        onUseDirectOnce = viewModel::retryWithDirectOnce
                    )

                    // 取消且没有任何数据：中性占位 + 重试。
                    // 绝不能落到下面的 EmptyBlock，否则就是「取消一次 = 本月没有账单」的假结论。
                    state.cancelledWithoutData -> CancelledBlock(onRetry = viewModel::refresh)

                    state.isEmpty -> EmptyBlock(mode = state.mode)

                    else -> BillList(
                        state = state,
                        onOpenDetail = viewModel::openDetail,
                        onOpenMonth = viewModel::openMonth,
                        onLoadMore = viewModel::loadMore
                    )
                }
            }
        }
    }
}

@Composable
private fun BillModeTabs(mode: LegacyCampusCardMode, onSelect: (LegacyCampusCardMode) -> Unit) {
    TabRow(
        selectedTabIndex = if (mode == LegacyCampusCardMode.BILL) 0 else 1,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Tab(
            selected = mode == LegacyCampusCardMode.BILL,
            onClick = { onSelect(LegacyCampusCardMode.BILL) },
            text = { Text(stringResource(R.string.bill_mode_bill)) }
        )
        Tab(
            selected = mode == LegacyCampusCardMode.FLOW,
            onClick = { onSelect(LegacyCampusCardMode.FLOW) },
            text = { Text(stringResource(R.string.bill_mode_flow)) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BillList(
    state: CampusCardBillUiState,
    onOpenDetail: (LegacyCampusCardEntry) -> Unit,
    onOpenMonth: (String) -> Unit,
    onLoadMore: () -> Unit
) {
    val grouped = remember(state.entries) { state.entries.groupBy { it.month } }
    val listState = rememberLazyListState()

    // 刷新失败 / 被取消时，提示虽然插在最顶部，但列表仍停在用户原来的滚动位置 ——
    // 不主动滚回去就等于没提示。这里只在「提示刚刚出现」那一刻滚一次，
    // 依赖 errorMessage / cancelled 本身而不是列表内容，避免翻页时被反复打断。
    LaunchedEffect(state.errorMessage, state.cancelled) {
        if (state.entries.isNotEmpty() && (state.errorMessage != null || state.cancelled)) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 刷新失败 / 刷新被取消：必须贴在**顶部**。
        // 以前这两条放在列表末尾（99 笔数据之后），有数据时用户根本滚不到，等于没有提示。
        if (state.errorMessage != null && state.entries.isNotEmpty()) {
            item(key = "inline-error") {
                InlineNotice(
                    text = state.errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        if (state.cancelled && state.entries.isNotEmpty()) {
            item(key = "inline-cancelled") {
                InlineNotice(
                    text = stringResource(R.string.bill_cancelled_refresh),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.mode == LegacyCampusCardMode.BILL) {
            item(key = "summary") {
                BillSummaryCard(state = state)
                Spacer(Modifier.height(4.dp))
            }
        } else {
            item(key = "flow-summary") {
                FlowSummaryCard(total = state.total)
                Spacer(Modifier.height(4.dp))
            }
        }

        grouped.forEach { (month, entries) ->
            stickyHeader(key = "month-$month") {
                MonthHeader(
                    month = month,
                    showMonthBill = state.mode == LegacyCampusCardMode.BILL,
                    onOpenMonth = { onOpenMonth(month) }
                )
            }

            items(items = entries, key = { it.id }) { entry ->
                EntryRow(
                    entry = entry,
                    showAccountType = state.mode == LegacyCampusCardMode.FLOW,
                    onClick = { onOpenDetail(entry) }
                )
            }
        }

        item(key = "footer") {
            if (state.hasMore) {
                LaunchedEffect(state.entries.size) { onLoadMore() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (state.entries.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.bill_no_more),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BillSummaryCard(state: CampusCardBillUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SummaryCell(
                icon = Icons.Rounded.AccountBalanceWallet,
                label = stringResource(R.string.bill_latest_balance),
                value = state.latestBalance?.let { "¥${formatAmount(it)}" } ?: "—",
                modifier = Modifier.weight(1f)
            )
            SummaryCell(
                icon = Icons.Rounded.CalendarMonth,
                label = stringResource(R.string.bill_total_expense),
                value = state.monthExpense?.let { "¥${formatAmount(it)}" } ?: "—",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SummaryCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FlowSummaryCard(total: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = stringResource(R.string.bill_total_count, total),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MonthHeader(
    month: String,
    showMonthBill: Boolean,
    onOpenMonth: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        // 与页面底色（background）同色，而不是 surface。
        // 这里**不能**真设成透明：它是 stickyHeader，滚动时必须不透明才能盖住下面的内容。
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = monthLabel(month),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (showMonthBill) {
                TextButton(onClick = onOpenMonth) {
                    Text(stringResource(R.string.bill_month_bill))
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: LegacyCampusCardEntry,
    showAccountType: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EntryIcon(entry = entry)

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.merchant.ifBlank { entry.transName },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(entry.time)
                        if (entry.transName.isNotBlank()) {
                            append(" · ")
                            append(entry.transName)
                        }
                        if (showAccountType && !entry.accountType.isNullOrBlank()) {
                            append(" · ")
                            append(entry.accountType)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = signedAmount(entry),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (entry.isIncome) IncomeColor else ExpenseColor
                )
                entry.cardBalance?.let { balance ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.bill_balance_short, formatAmount(balance)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 交易行的圆形容器图标。
 *
 * 关键词规则见 [legacyEntryIconKind]（纯函数，有单测）。
 * 默认类保持原样：收入绿 / 消费红；其余分类用「自身颜色 12% 淡底 + 同色图形」，
 * 这样一列里既有辨识度又不会因为深色底把彩色 logo 弄脏。
 *
 * U净 是唯一的例外：**实心原 SVG 紫底 + 白色 logo**。
 * 它占真实流水 30/86 笔（最高频），值得单独强调；而且它的「U」形笔画偏细，
 * 淡色底上几乎看不出形状。
 */
@Composable
private fun EntryIcon(
    entry: LegacyCampusCardEntry,
    size: Dp = 38.dp,
    iconSize: Dp = 20.dp
) {
    val kind = legacyEntryIconKind(entry.merchant, entry.transName)

    if (kind == LegacyEntryIconKind.UJING) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(UjingBrand),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ujing_logo),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(iconSize * 1.1f)
            )
        }
        return
    }

    val tint = when (kind) {
        LegacyEntryIconKind.DINING -> Color(0xFFE8873A)
        LegacyEntryIconKind.WECHAT -> Color(0xFF07C160)
        LegacyEntryIconKind.NETWORK -> Color(0xFF2E86C1)
        LegacyEntryIconKind.ELECTRIC -> Color(0xFFF2A93B)
        LegacyEntryIconKind.ACCOUNT_SETTING -> Color(0xFF7A7A85)
        else -> if (entry.isIncome) IncomeColor else ExpenseColor
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        if (kind == LegacyEntryIconKind.WECHAT) {
            Image(
                painter = painterResource(R.drawable.ic_wechat),
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(iconSize)
            )
        } else {
            Icon(
                imageVector = when (kind) {
                    LegacyEntryIconKind.DINING -> Icons.Rounded.RiceBowl
                    LegacyEntryIconKind.NETWORK -> Icons.Rounded.Wifi
                    LegacyEntryIconKind.ELECTRIC -> Icons.Rounded.ElectricBolt
                    LegacyEntryIconKind.ACCOUNT_SETTING -> Icons.Rounded.Settings
                    else -> Icons.AutoMirrored.Rounded.ReceiptLong
                },
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = tint
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.loading_campus_card_bill),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorBlock(
    message: String,
    needLogin: Boolean,
    offerWebVpnOnce: Boolean,
    offerDirectOnce: Boolean,
    onRetry: () -> Unit,
    onUseWebVpnOnce: () -> Unit,
    onUseDirectOnce: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(28.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) {
                Text(
                    if (needLogin) stringResource(R.string.bill_action_relogin)
                    else stringResource(R.string.action_retry)
                )
            }
            // 换通道的出路：失败在哪条通道，就给出另一条。都只对本次生效，不翻转全局开关。
            if (offerWebVpnOnce) {
                TextButton(onClick = onUseWebVpnOnce) {
                    Text(stringResource(R.string.action_use_webvpn_once))
                }
            }
            if (offerDirectOnce) {
                TextButton(onClick = onUseDirectOnce) {
                    Text(stringResource(R.string.action_use_direct_once))
                }
            }
        }
    }
}

/** 列表顶部的行内提示：刷新失败 / 刷新被取消。 */
@Composable
private fun InlineNotice(text: String, color: Color) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}

/**
 * 取消后的中性占位。
 *
 * 用词刻意避开「没有账单」：用户只是把验证码弹窗关掉了，我们**不知道**这个月有没有账单。
 */
@Composable
private fun CancelledBlock(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(28.dp)
        ) {
            Text(
                text = stringResource(R.string.bill_cancelled),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun EmptyBlock(mode: LegacyCampusCardMode) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(
                if (mode == LegacyCampusCardMode.BILL) R.string.bill_empty else R.string.bill_flow_empty
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ------------------- 详情 -------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDetailSheet(entry: LegacyCampusCardEntry, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.bill_detail_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = signedAmount(entry),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (entry.isIncome) IncomeColor else ExpenseColor
            )
            HorizontalDivider()
            DetailRow(stringResource(R.string.bill_detail_merchant), entry.merchant)
            DetailRow(stringResource(R.string.bill_detail_time), entry.occurredAt)
            DetailRow(stringResource(R.string.bill_detail_type), entry.transName)
            entry.account?.let { DetailRow(stringResource(R.string.bill_detail_account), it) }
            entry.description?.let { DetailRow(stringResource(R.string.bill_detail_description), it) }
            DetailRow(stringResource(R.string.bill_detail_serial), entry.serial)
            entry.effectAt?.let { DetailRow(stringResource(R.string.bill_detail_effect_time), it) }
            entry.cardBalance?.let {
                DetailRow(stringResource(R.string.bill_detail_balance), "¥${formatAmount(it)}")
            }
            entry.accountType?.let {
                DetailRow(stringResource(R.string.bill_detail_account_type), it)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = label,
            modifier = Modifier.width(88.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

// ------------------- 月账单 -------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthBillSheet(
    state: MonthSheetState,
    onDismiss: () -> Unit,
    onSelectCategory: (LegacyBillCategory) -> Unit,
    onSelectYear: (Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.bill_month_summary_title, monthLabel(state.month)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            when {
                state.isLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }

                state.errorMessage != null -> Text(
                    text = state.errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )

                state.categories.isEmpty() -> Text(
                    text = stringResource(R.string.bill_month_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> {
                    // 原站把「本月总消费」和环形图放一起，这里沿用同样的组合：
                    // 环形中心直接写总额，省掉原来那张纯文字卡。
                    MonthlyDonut(categories = state.categories)

                    state.categories.forEachIndexed { index, category ->
                        CategoryRow(
                            category = category,
                            color = legacyBillColor(index),
                            percent = legacyBillPercent(category.amount, state.totalExpense),
                            selected = state.selectedCategory?.code == category.code,
                            onClick = { onSelectCategory(category) }
                        )
                    }

                    state.selectedCategory?.let { category ->
                        HorizontalDivider()
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (state.isCategoryLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        } else if (state.categoryRows.isEmpty()) {
                            Text(
                                text = stringResource(R.string.bill_month_no_data),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            state.categoryRows.forEach { entry ->
                                CompactEntryRow(entry)
                            }
                        }
                    }

                    YearTrend(
                        year = state.year,
                        totals = state.yearTotals,
                        isLoading = state.isYearLoading,
                        errorMessage = state.yearError,
                        onSelectYear = onSelectYear
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: LegacyBillCategory,
    color: Color,
    percent: Float,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 与环形图扇区同色的小圆点，把「哪块饼是哪个分类」直接连起来
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatLegacyPercent(percent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "¥${formatAmount(category.amount)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompactEntryRow(entry: LegacyCampusCardEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EntryIcon(entry = entry, size = 30.dp, iconSize = 16.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.merchant.ifBlank { entry.transName },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${entry.date} ${entry.time}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = signedAmount(entry),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (entry.isIncome) IncomeColor else ExpenseColor
        )
    }
}

/**
 * 年度趋势（横向条形）。
 *
 * 年份可切换：原站 `EveryMonthPay` 实测接受任意年份（2026 `maxamt=124.72`、2025 `155.19`、
 * 2024/2023 全 0 —— 是「确实没消费」而不是报错）。
 *
 * 切换只重载这张图，不影响上面的分类与明细：那些绑定的是**月份**，与年份无关。
 */
@Composable
private fun YearTrend(
    year: Int,
    totals: LegacyYearTotals?,
    isLoading: Boolean,
    errorMessage: String?,
    onSelectYear: (Int) -> Unit
) {
    val range = remember { legacyYearRange() }
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.bill_year_trend),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        YearStepper(
            year = year,
            canGoBack = year > range.first,
            canGoForward = year < range.last,
            onSelectYear = onSelectYear
        )
    }

    when {
        isLoading -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }

        errorMessage != null -> Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )

        // 该年确实一笔消费都没有：说清楚是「这一年没有」，而不是画一排 0 让人猜
        totals == null || totals.amounts.all { it <= 0.0 } -> Text(
            text = stringResource(R.string.bill_year_no_data, year),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        else -> {
            val max = totals.max.takeIf { it > 0 } ?: (totals.amounts.maxOrNull() ?: 0.0)
            totals.amounts.forEachIndexed { index, amount ->
                val label = totals.months.getOrNull(index) ?: (index + 1).toString()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        modifier = Modifier.width(52.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { if (max <= 0) 0f else (amount / max).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = formatAmount(amount),
                        modifier = Modifier.width(56.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

/** `‹ 2025 ›` 年份步进器。 */
@Composable
private fun YearStepper(
    year: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onSelectYear: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onSelectYear(year - 1) },
            enabled = canGoBack,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Rounded.ChevronLeft,
                contentDescription = stringResource(R.string.bill_year_prev),
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = year.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.widthIn(min = 44.dp),
            textAlign = TextAlign.Center
        )
        IconButton(
            onClick = { onSelectYear(year + 1) },
            enabled = canGoForward,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = stringResource(R.string.bill_year_next),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ------------------- 环形图 -------------------

/**
 * 月消费环形图 —— 对齐原站 ECharts 配置：
 * `radius: ['20%','70%']`、`center: ['50%','60%']`、`color: [4 色]`。
 *
 * 中心直接写本月总支出，替代原来那张纯文字卡。
 */
@Composable
private fun MonthlyDonut(categories: List<LegacyBillCategory>) {
    val total = categories.sumOf { it.amount }
    val slices = remember(categories) { legacyBillSlices(categories) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(200.dp)) {
            val stroke = size.minDimension * 0.25f
            val radius = (size.minDimension - stroke) / 2f
            if (slices.isEmpty()) {
                // 没有任何消费：画一圈轨道，避免空白
                drawCircle(
                    color = Color(0x1FFFFFFF),
                    radius = radius,
                    style = Stroke(width = stroke)
                )
            } else {
                slices.forEachIndexed { index, slice ->
                    // 扇区之间留 1.5° 细缝，靠减 sweep 而不是加间隙，保证角度总和仍是 360°
                    val gap = if (slices.size > 1) 1.5f else 0f
                    drawArc(
                        color = legacyBillColor(index),
                        startAngle = slice.startAngle + gap / 2f,
                        sweepAngle = (slice.sweepAngle - gap).coerceAtLeast(0.5f),
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt)
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.bill_total_expense),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "¥${formatAmount(total)}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ------------------- 小工具 -------------------

/** 金额格式化：两位小数（与旧平台 `toFixed(2)` 一致）。 */
private fun formatAmount(value: Double): String = formatLegacyAmount(value)

/** 带符号金额：消费 `-¥x.xx`，收入 `+¥x.xx`。 */
private fun signedAmount(entry: LegacyCampusCardEntry): String =
    formatLegacySignedAmount(entry.amount)

/** `2026-10` →「本月」/「2026年10月」。 */
@Composable
private fun monthLabel(month: String): String {
    val current = YearMonth.now().toString()
    if (month == current) return stringResource(R.string.bill_current_month)
    val year = month.take(4).toIntOrNull()
    val monthNumber = month.drop(5).take(2).toIntOrNull()
    return if (year != null && monthNumber != null) {
        stringResource(R.string.bill_month_format, year, monthNumber)
    } else {
        month
    }
}
