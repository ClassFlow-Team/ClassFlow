package com.shiro.classflow.ui.campus.ujing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.NavBridge
import com.xingheyuzhuan.shiguangschedule.R
import com.shiro.classflow.ui.campus.components.WbuAuthTipsScenario
import com.shiro.classflow.ui.campus.components.WbuCampusAuthSheet
import com.xingheyuzhuan.shiguangschedule.ui.components.UjingBrandLoading

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UjingWaterScreen(
    cd: String,
    scanId: Long = 0L,
    navBridge: NavBridge,
    viewModel: UjingWaterViewModel = hiltViewModel()
) {
    // 本进程有没有受理过这个页面（只在内存里，重建后一定是 false）。
    // 它和「保存状态」里的那个标记配一起，才能区分「这次新打开」和「被系统恢复出来的旧页面」。
    var handledInProcess by remember(cd) { mutableStateOf(false) }
    var handledInSavedState by rememberSaveable(cd) { mutableStateOf(false) }

    // 系统用保存状态恢复出来的旧取水页（进程被杀后回来、Activity 重建）：本进程已经没有进行中的
    // 订单了（订单信息只在内存里，不落盘），这页面拿不到订单的任何信息，显示什么都是错的。
    // 所以什么都不渲染，直接退回上一页 —— 不重新下单，也不挂在「正在连接饮水机…」上误导人。
    //
    // 判据里**必须**带上 ViewModel 自己记的「处理过这次进入」：
    // `remember` 在「页面被别的页面盖住又回来」时也会丢（从欠费卡片点「去充值」就是这个场景），
    // 只看 `!handledInProcess` 会把**还活着的页面**误判成死页面，于是用户充完值回来，
    // 页面自己 popBackStack 消失，落在课表 / 一卡通主页上 —— 结算也就无从继续。
    // ViewModel 按导航条目存活，它说「我手里有这一单」，页面就一定有状态可显示。
    val deadRestoredPage = isDeadRestoredWaterPage(
        handledInProcess = handledInProcess,
        handledInSavedState = handledInSavedState,
        viewModelHasOrder = viewModel.hasProcessedEntry(),
        hasResumableSession = UjingWaterViewModel.hasResumableSession(cd),
    )

    if (deadRestoredPage) {
        LaunchedEffect(cd) { navBridge.popBackStack() }
        return
    }

    // 本页自带 U净 品牌首屏（logo + 转圈），不再叠加 ClassFlow 过场动画：
    // 两段品牌动画先后出现反而割裂，只保留 U净 这一段。
    Box(modifier = Modifier.fillMaxSize()) {
        val uiState by viewModel.uiState.collectAsState()

        LaunchedEffect(cd) {
            if (cd.isNotBlank()) {
                handledInProcess = true
                handledInSavedState = true
                // 这台设备还在出水就接着看那一单（绝不会再下一单），没有才开新一轮
                viewModel.start(cd)
            }
        }

        // 退出页面只是不看了，不等于订单结束：会话留在内存里，再碰 NFC 还能接着看这一单，
        // 而不是把正在出水的订单丢掉、下次碰卡又去下一单（服务端会以「设备正在使用中」拒绝）。
        BackHandler {
            navBridge.popBackStack()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.title_ujing_water)) },
                    navigationIcon = {
                        IconButton(onClick = { navBridge.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.action_exit)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                val stage = uiState.stage
                // Splash → Loading 走同一个 composable 实例：logo 不重播淡入，
                // 入场转场与首屏等待读起来才是同一段动画
                if (stage is UjingWaterUiStage.Splash || stage is UjingWaterUiStage.Loading) {
                    UjingBrandLoading(
                        message = when (stage) {
                            is UjingWaterUiStage.Loading -> stage.message
                            else -> stringResource(R.string.ujing_water_connecting)
                        },
                        showSpinner = stage is UjingWaterUiStage.Loading
                    )
                } else when (stage) {
                    is UjingWaterUiStage.Active -> {
                        ActiveDispensingView(
                            stage = stage,
                            // 出水还在进行：只关页面，订单会话留着（见上面的 BackHandler 注释）
                            onDone = { navBridge.popBackStack() }
                        )
                    }

                    is UjingWaterUiStage.Finished -> {
                        FinishedView(
                            stage = stage,
                            settleNotice = uiState.settleNotice,
                            onRepeat = { viewModel.restart(cd) },
                            onSettle = viewModel::settleCurrentOrder,
                            onRecharge = { viewModel.rechargeCampusCard(navBridge) },
                            onDone = {
                                UjingWaterViewModel.clearSession()
                                navBridge.popBackStack()
                            },
                            onContinueScan = {
                                UjingWaterViewModel.clearSession()
                                navBridge.replace(Destination.QrScan)
                            }
                        )
                    }

                    is UjingWaterUiStage.Error -> {
                        ErrorView(
                            message = stage.message,
                            canRetry = stage.canRetry,
                            offerWebVpnOnce = stage.offerWebVpnOnce,
                            unpaidBlocksOrder = stage.unpaidBlocksOrder,
                            settleNotice = uiState.settleNotice,
                            onRetry = viewModel::retry,
                            onRetryWithWebVpnOnce = viewModel::retryWithWebVpnOnce,
                            onSettleAndRetry = viewModel::settleThenRetry,
                            onRecharge = { viewModel.rechargeCampusCard(navBridge) },
                            onExit = {
                                UjingWaterViewModel.clearSession()
                                navBridge.popBackStack()
                            }
                        )
                    }
                }

                if (uiState.needLogin) {
                    WbuCampusAuthSheet(
                        onDismiss = { viewModel.onLoginDismissed() },
                        onLoginSuccess = { viewModel.onLoginSuccess() },
                        requireUnifiedCas = true,
                        unifiedAuthOnly = true,
                        title = stringResource(R.string.service_campus_card),
                        tipsScenario = WbuAuthTipsScenario.IDENTITY
                    )
                }
            }
        }
    }
}


@Composable
private fun ActiveDispensingView(
    stage: UjingWaterUiStage.Active,
    onDone: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.WaterDrop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stage.detail?.orderStatusName ?: stringResource(R.string.ujing_water_dispensing),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stage.subject.subjectName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )

                if (stage.mqttConnected) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.ujing_water_realtime_connected),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 实时取水量卡片
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.ujing_water_order_info),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                InfoRow(label = stringResource(R.string.ujing_water_hot_amount), value = "${stage.detail?.hotWaterMl ?: 0} mL")
                InfoRow(label = stringResource(R.string.ujing_water_warm_amount), value = "${stage.detail?.warmWaterMl ?: 0} mL")
                InfoRow(label = stringResource(R.string.ujing_water_pay_type), value = stage.detail?.payTypeName ?: stringResource(R.string.label_campus_card_quick_pay))
                InfoRow(label = stringResource(R.string.ujing_water_order_no), value = stage.orderResult.orderNo ?: "${stage.orderResult.orderId}")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            // 「等待结账」时水已经取完了，还写「请在饮水机按键接水」就是在骗人
            text = if (stage.detail?.orderStatus == 30) {
                stringResource(R.string.ujing_water_waiting_settle_hint)
            } else {
                stringResource(R.string.ujing_water_action_hint)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_qr_scan_done))
        }
    }
}

@Composable
private fun FinishedView(
    stage: UjingWaterUiStage.Finished,
    settleNotice: WaterUnpaidNotice?,
    onRepeat: () -> Unit,
    onSettle: () -> Unit,
    onRecharge: () -> Unit,
    onDone: () -> Unit,
    onContinueScan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ujing_water_finished_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stage.subject.subjectName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                InfoRow(label = stringResource(R.string.ujing_water_hot_amount), value = "${stage.detail.hotWaterMl} mL")
                InfoRow(label = stringResource(R.string.ujing_water_warm_amount), value = "${stage.detail.warmWaterMl} mL")
                InfoRow(label = stringResource(R.string.ujing_water_cost), value = "¥ ${String.format("%.2f", stage.detail.payPrice)}")
                InfoRow(label = stringResource(R.string.ujing_water_pay_type), value = stage.detail.payTypeName ?: stringResource(R.string.label_campus_card_quick_pay))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        UnpaidNoticeCard(notice = settleNotice)

        // 完成页也一样：欠着钱就给出路（充值 / 补缴同一行，与错误页一致）
        if (settleNotice is WaterUnpaidNotice.Unsettled) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRecharge, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_recharge_campus_card))
                }
                OutlinedButton(onClick = onSettle, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_settle_now))
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRepeat,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Rounded.WaterDrop,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.ujing_water_repeat))
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_qr_scan_done))
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onContinueScan,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Rounded.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.action_continue_scan))
        }
    }
}

@Composable
private fun ErrorView(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    offerWebVpnOnce: Boolean = false,
    onRetryWithWebVpnOnce: () -> Unit = {},
    unpaidBlocksOrder: Boolean = false,
    settleNotice: WaterUnpaidNotice? = null,
    onSettleAndRetry: () -> Unit = {},
    onRecharge: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            // 欠费挡着下单不是「页面加载失败」——那是在说网络坏了，会让人白折腾
            text = if (unpaidBlocksOrder) {
                stringResource(R.string.ujing_water_unpaid_blocks_title)
            } else {
                stringResource(R.string.title_load_failed)
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        // 欠费挡着下单：重试没用，先把账结清
        if (unpaidBlocksOrder) {
            UnpaidNoticeCard(notice = settleNotice)
            Spacer(modifier = Modifier.height(20.dp))

            // 一卡通扣款失败基本都是余额不足 —— 先去充值，回来再结清。
            // 两个动作同一行：它们是同一条路的两步，不该分主次两层排。
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onRecharge ?: onSettleAndRetry,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_recharge_campus_card))
                }
                OutlinedButton(
                    onClick = onSettleAndRetry,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_settle_and_retry))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 退出放在最后一行，与主操作拉开距离（误触退出就得多扫一次）
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onExit) {
                Text(stringResource(R.string.action_exit))
            }
            // 欠费场景下「重试」是无效动作（账不结清永远同一句），不给
            if (canRetry && !unpaidBlocksOrder) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
        // 被校园网关拦在外面时，重试只会再撞一次；换一条通道才可能真的通
        if (offerWebVpnOnce) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRetryWithWebVpnOnce) {
                Text(stringResource(R.string.action_use_webvpn_once))
            }
        }
    }
}

/**
 * 欠费单的状态卡片：**已自动补缴** / **没动账，原因是什么**。
 *
 * 刻意做成一张安静的小卡片而不是红色错误：水已经取到了（或者马上就能取），
 * 这里说的是钱的事，不该把整页变成「失败」。
 */
@Composable
private fun UnpaidNoticeCard(notice: WaterUnpaidNotice?) {
    if (notice == null) return
    val isSettled = notice is WaterUnpaidNotice.Settled
    val amount = when (notice) {
        is WaterUnpaidNotice.Settled -> notice.amount
        is WaterUnpaidNotice.Unsettled -> notice.amount
    }
    val orderNo = when (notice) {
        is WaterUnpaidNotice.Settled -> notice.orderNo
        is WaterUnpaidNotice.Unsettled -> notice.orderNo
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSettled) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.ujing_water_unpaid_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            InfoRow(
                label = stringResource(R.string.ujing_water_unpaid_amount),
                value = "¥ ${String.format("%.2f", amount)}"
            )
            orderNo?.let {
                InfoRow(label = stringResource(R.string.ujing_water_order_no), value = it)
            }
            Spacer(modifier = Modifier.height(8.dp))

            when (notice) {
                is WaterUnpaidNotice.Settled -> {
                    Text(
                        text = stringResource(R.string.ujing_water_auto_settled, String.format("%.2f", notice.amount)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                is WaterUnpaidNotice.Unsettled -> {
                    Text(
                        text = unsettledReasonText(notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    // 按钮不在这里出：欠费单的出路（充值 / 补缴）由页面底部统一排，
                    // 免得同一张卡片里再堆两个按钮，和下面那排抢注意力。
                }
            }
        }
    }
}

/** 没结成的那句人话（服务端原话优先）。 */
@Composable
private fun unsettledReasonText(notice: WaterUnpaidNotice.Unsettled): String {
    val appContext = LocalContext.current
    return when (notice.reason) {
        WaterUnpaidReason.AUTO_SETTLE_DISABLED ->
            stringResource(R.string.ujing_water_unpaid_auto_disabled)

        WaterUnpaidReason.NOT_SMART_CARD ->
            stringResource(
                R.string.ujing_water_unpaid_not_smart_card,
                notice.serverMessage ?: appContext.getString(R.string.label_campus_card_quick_pay)
            )

        WaterUnpaidReason.NO_ORDER_NO ->
            stringResource(R.string.ujing_water_unpaid_no_order_no)

        WaterUnpaidReason.PENDING ->
            stringResource(R.string.ujing_water_settle_pending)

        // 一卡通扣款没成功（实测错误码 80，多半是余额不足）：说清楚「充钱再试」，
        // 不把用户推去「联系一卡通」—— 那条路解决不了余额问题
        WaterUnpaidReason.INSUFFICIENT_FUNDS ->
            stringResource(R.string.ujing_water_settle_insufficient_funds)

        WaterUnpaidReason.SERVER_REJECTED, WaterUnpaidReason.REQUEST_FAILED ->
            stringResource(
                R.string.ujing_water_settle_failed,
                notice.serverMessage ?: appContext.getString(R.string.err_unpaid_water_order)
            )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
