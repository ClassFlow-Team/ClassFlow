package com.shiro.classflow.ui.campus.ujing

import com.xingheyuzhuan.shiguangschedule.R

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.shiro.classflow.data.network.NoNetworkException
import com.shiro.classflow.data.network.wbu.AccessFailure
import com.shiro.classflow.data.network.wbu.AccessLayer
import com.shiro.classflow.data.network.wbu.UjingMqttClient
import com.shiro.classflow.data.network.wbu.WafBlockedException
import com.shiro.classflow.data.network.wbu.WbuAuthTransport
import com.shiro.classflow.data.network.wbu.WbuCampusCardClient
import com.shiro.classflow.data.network.wbu.WbuFailureDetector
import com.shiro.classflow.data.network.wbu.WbuSessionExpiredException
import com.shiro.classflow.data.network.wbu.UjingPayFlag
import com.shiro.classflow.data.network.wbu.WbuUjingClient
import com.shiro.classflow.data.network.wbu.directChannelTrouble
import com.shiro.classflow.data.network.wbu.canAttemptSettle
import com.shiro.classflow.data.network.wbu.isDispenseDone
import com.shiro.classflow.data.network.wbu.isOutstanding
import com.shiro.classflow.data.network.wbu.needsRelogin
import com.shiro.classflow.data.network.wbu.pickOutstandingOrder
import com.shiro.classflow.data.model.wbu.CampusDeviceEntry
import com.shiro.classflow.data.model.wbu.CampusDeviceKind
import com.xingheyuzhuan.shiguangschedule.data.repository.CampusDeviceHubRepository
import com.xingheyuzhuan.shiguangschedule.ui.components.accessFailureText
import com.xingheyuzhuan.shiguangschedule.ui.components.shouldOfferWebVpnOnce
import com.xingheyuzhuan.shiguangschedule.ui.components.silentUnifiedAuthLogin
import com.xingheyuzhuan.shiguangschedule.ui.components.userFacingMessage
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface UjingWaterUiStage {
    data object Splash : UjingWaterUiStage
    data class Loading(val message: String) : UjingWaterUiStage
    data class Active(
        val subject: WbuUjingClient.WaterServiceSubject,
        val orderResult: WbuUjingClient.WaterOrderResult,
        val detail: WbuUjingClient.WaterOrderDetail?,
        val mqttConnected: Boolean
    ) : UjingWaterUiStage
    data class Finished(
        val subject: WbuUjingClient.WaterServiceSubject,
        val detail: WbuUjingClient.WaterOrderDetail
    ) : UjingWaterUiStage
    data class Error(
        val message: String,
        val canRetry: Boolean = true,
        /**
         * 是否给一个「改用 WebVPN」的出路。
         *
         * 只在「直连这条通道走不通」时出现：被校外 WAF 拦（[com.shiro.classflow.data.network.wbu.WafBlockedException]）
         * 或一个响应都没拿到。会话过期不给 —— 那种情况该重新登录，换通道只会再撞一次。
         */
        val offerWebVpnOnce: Boolean = false,
        /**
         * 这次失败是不是「有未支付订单挡着」（服务端 `1532`）。
         *
         * 是的话页面多给一个「先结清再试」的按钮 —— 重试本身没用，账不结清永远下不了单。
         */
        val unpaidBlocksOrder: Boolean = false
    ) : UjingWaterUiStage
}

/**
 * 欠费单的处理结果（取水页展示用）。
 *
 * 与「取水」本身解耦：水可能已经取完了，只是钱没结清 —— 那种情况不该掉进 [UjingWaterUiStage.Error]。
 */
sealed interface WaterUnpaidNotice {
    /** 已经替用户自动补缴成功。 */
    data class Settled(val amount: Double, val orderNo: String?) : WaterUnpaidNotice

    /** 查到了欠费，但没动账（开关关着 / 不是一卡通直扣 / 服务端拒绝 / 网络不通）。 */
    data class Unsettled(
        val amount: Double,
        val orderNo: String?,
        val reason: WaterUnpaidReason,
        /** 服务端原话（有的话），比我们自己的兜底文案更有用。 */
        val serverMessage: String? = null
    ) : WaterUnpaidNotice
}

/** 为什么没替用户把这笔账结掉。 */
enum class WaterUnpaidReason {
    /** 「自动补缴饮水欠费」开关关着。 */
    AUTO_SETTLE_DISABLED,

    /** 这单不是一卡通直扣（微信 / 支付宝 / 云闪付 / 电子账户），我们起不来对应的钱包。 */
    NOT_SMART_CARD,

    /** 订单没有 `orderNo`，没法结。 */
    NO_ORDER_NO,

    /**
     * 一卡通扣款没成功 —— **多半是余额不足**，充钱后重试就能过。
     *
     * 实测：`genPayArgs` 回 `1021`「一卡通异常，请联系一卡通处理：支付未成功，错误码：80」，
     * 充值后同一请求立刻 `code: 0 success`。所以这类失败要给「去充值」的出路，
     * 而不是「请联系一卡通」（那是在把用户推给一个他并不需要的流程）。
     */
    INSUFFICIENT_FUNDS,

    /** 服务端明确拒绝了补缴（非扣款失败类）。 */
    SERVER_REJECTED,

    /**
     * 服务端回「请勿重复请求」（`6020208`）且复查后仍未结清。
     *
     * 不是失败：这笔补缴它收到了、正在处理。给用户一句「正在处理」远好过
     * 「补缴失败：请勿重复请求」—— 后者看起来像我们写坏了。
     */
    PENDING,

    /** 请求本身没成功（网络 / 会话）。 */
    REQUEST_FAILED,
}

/**
 * 这个补缴失败是不是「充钱就能好」的那一类。
 *
 * `1021` 是一卡通渠道的**扣款失败**（实测错误码 80 = 支付未成功，典型成因余额不足）。
 * 判定刻意只看业务码、不去猜 message 里的字样：文案会变，码不会。
 */
fun isRetriableSettleFailure(e: WbuUjingClient.UjingApiException): Boolean =
    e.code == WbuUjingClient.CODE_CARD_PAY_FAILED

/**
 * 这个错误是不是「账号名下还有一笔没走完的单，所以不能开新单」。
 *
 * 两个码都算：
 * - [WbuUjingClient.CODE_ORDER_NOT_FINISHED]（`2040224`）—— 本校实测就是这个；
 * - [WbuUjingClient.CODE_UNPAID_ORDER]（`1532`）—— 官方 H5 里的「支付宝订单未结账」分支。
 *
 * 注意它只说「有单没结束」，**不保证那单欠费**：可能正在出水。所以上层要么先查列表
 * 确认，要么在结不掉时如实告诉用户「请先去结束那一单」。
 */
fun WbuUjingClient.UjingApiException.blocksDispensing(): Boolean =
    code == WbuUjingClient.CODE_ORDER_NOT_FINISHED || code == WbuUjingClient.CODE_UNPAID_ORDER

data class UjingWaterUiState(
    val stage: UjingWaterUiStage = UjingWaterUiStage.Splash,
    val cd: String = "",
    val needLogin: Boolean = false,
    /**
     * 最近一次「欠费单」的处理结果（自动补缴成功 / 没动账的原因）。
     *
     * 与 [stage] 平行而不是塞进某个阶段：这件事可能在**取水前**发生（拦住了下单），
     * 也可能在**取水后**发生（水取完了钱没扣到），两者都要在页面上说得清楚。
     */
    val settleNotice: WaterUnpaidNotice? = null
)

@HiltViewModel
class UjingWaterViewModel @Inject constructor(
    application: Application,
    private val deviceHubRepository: CampusDeviceHubRepository,
) : AndroidViewModel(application) {

    data class WaterSession(
        val cd: String,
        val orderId: Long,
        val subject: WbuUjingClient.WaterServiceSubject,
        val stage: UjingWaterUiStage,
        val timestamp: Long
    )

    companion object {
        private const val TAG = "UjingWaterViewModel"
        private const val SESSION_VALID_DURATION_MS = 20 * 60 * 1000L // 20 分钟内防重入

        @Volatile
        private var lastSession: WaterSession? = null

        fun clearSession() {
            lastSession = null
        }

        /**
         * 本进程里这台设备是否还有**正在进行中**的取水订单（20 分钟内有效）。
         *
         * 取水会话只活在内存里（订单号、取水点都在其中），不落盘，所以：
         * - 为 true：订单还在跑，订单信息也还拿得到。再碰 NFC / 再扫码都不要再开一单
         *   （一个账号同时只有一个出水点），页面可以接着显示；
         * - 为 false：本进程没有这张订单的任何信息 —— 那就别装出「正在取水」的样子：
         *   重建出来的旧页面靠它判断自己该不该直接退掉。
         */
        fun hasResumableSession(cd: String): Boolean {
            val session = lastSession ?: return false
            return session.cd == cd.trim() &&
                session.stage is UjingWaterUiStage.Active &&
                System.currentTimeMillis() - session.timestamp < SESSION_VALID_DURATION_MS
        }
    }

    private val cardClient = WbuCampusCardClient(application, useVpn = false)
    private val ujingClient = WbuUjingClient(application, cardClient)

    private val _uiState = MutableStateFlow(UjingWaterUiState())
    val uiState: StateFlow<UjingWaterUiState> = _uiState.asStateFlow()

    private var mqttClient: UjingMqttClient? = null
    private var pollJob: Job? = null
    private var currentCd: String = ""
    private var hasStarted: Boolean = false

    /**
     * 这个 ViewModel 实例是否已经为本次进入跑过 [start]。
     *
     * 页面被**别的页面盖住**（从欠费卡片点「去充值」、去看账单…）再回来时，composition 会被
     * 重建，但 ViewModel 按导航条目保留着 —— 订单、进度、结算结果都还在。这时唯一正确的做法是
     * 把原状态接回来；重走一遍 [executeFlow] 会**再下一单**，而用户只是去充了个值。
     *
     * 它同时是「页面被恢复」与「进程被重建」的分界：进程死了就是一个全新实例，
     * 这里为 false，页面据此判断自己手里已经没有任何订单信息（见 `UjingWaterScreen`）。
     */
    private var processedEntry: Boolean = false

    /** 已经向使用记录补过店铺名的订单 id：轮询每 2.5 秒一次，同一单只补一次。 */
    private val namesFilledOrders = mutableSetOf<Long>()

    private fun namesFilledForOrder(orderId: Long): Boolean =
        !namesFilledOrders.add(orderId)

    /** 本次取水是否已经自动改过一次隧道：只自动试一次，失败就交给用户，避免来回切。 */
    private var autoTunnelTried: Boolean = false

    /**
     * 本次页面生命周期里**已经成功**补缴过的订单（按 **orderId** 去重）。
     *
     * 注意只记「成功」，不记「失败」：一卡通扣款失败最常见的成因是**余额不足**
     * （实测 `genPayArgs` 回 `1021`「一卡通异常…错误码：80」，充值后同一请求立刻
     * `code: 0 success`）。把失败也记进去，用户充完钱重进页面就再也不会自动重试 ——
     * 那恰恰是他最需要重试的时刻。
     *
     * **key 必须统一用 `orderId`**：`waterOrderList` 的列表项**没有 `orderNo` 字段**
     * （实测只有 `id`），而详情里有。之前一处用 `orderNo`、一处用 `orderId`，
     * 两个集合永远对不上，退避形同虚设 —— 表现为每次进页面都会补缴两次，
     * 第二次稳定拿到 `6020208 请勿重复请求`。
     */
    private val settledOrders = mutableSetOf<Long>()

    /**
     * 刚才补缴失败过的订单 + 失败时刻，用于**短时退避**。
     *
     * 只防「同一屏内连打」（进页面 + 下单被拒 + MQTT 推送可能连着触发），
     * 不阻止用户充完钱重进页面再试：超过 [settleRetryBackoffMs] 就重新放行。
     */
    private val settleBackoffUntil = mutableMapOf<Long, Long>()

    /** 退避窗口：远小于「去充个值再回来」的时间，又足以挡住同一秒内的连打。 */
    private val settleRetryBackoffMs = 30_000L

    /** 这次能不能对 [orderId] 发起补缴（成功过就不再试；失败要过了退避窗口）。 */
    private fun canAttemptSettle(orderId: Long, force: Boolean): Boolean =
        canAttemptSettle(orderId, settledOrders, settleBackoffUntil, System.currentTimeMillis(), force)

    /**
     * 「请勿重复请求」([]WbuUjingClient.CODE_DUPLICATE_REQUEST]) 不该算失败。
     *
     * 它的意思是「这笔单的补缴我收到了、正在处理」，而不是「我拒绝你」。
     * 真正常见的情形是：下单前补缴刚打过一次，下单被挡住后又强制打了一次。
     * 这时正确做法是**重新查一次订单状态**看结果，而不是给用户报错。
     */
    private suspend fun recheckAfterDuplicate(orderId: Long): WbuUjingClient.WaterOrderDetail? =
        runCatching { ujingClient.fetchOrderDetail(orderId) }.getOrNull()

    /**
     * 订单已经到终态、但这一单本身还欠着钱时，把它结掉。
     *
     * 与 [settleOutstandingOrder] 的区别：这里**认的是手上这一单**（不看列表），
     * 因为它是刚取完水的订单，优先结它天经地义，不必去猜账号下哪一单最该先付。
     *
     * @return 结清后重新拉到的详情（供完成页显示真实扣费）；没动账或失败返回 null，
     *   调用方沿用原详情 —— 水已经取完了，不能因为付不了款把页面变成错误页。
     */
    private suspend fun settleOrderIfUnpaid(
        detail: WbuUjingClient.WaterOrderDetail,
        force: Boolean = false,
    ): WbuUjingClient.WaterOrderDetail? {
        if (!UjingPayFlag.from(detail.payFlag).isOutstanding(detail.payPrice)) return null

        val app = getApplication<Application>()
        if (!force && !WbuAuthTransport.isAutoSettleWaterUnpaidEnabled(app)) {
            _uiState.update {
                it.copy(
                    settleNotice = WaterUnpaidNotice.Unsettled(
                        amount = detail.payPrice,
                        orderNo = detail.orderNo,
                        reason = WaterUnpaidReason.AUTO_SETTLE_DISABLED,
                    )
                )
            }
            return null
        }

        if (detail.waterForceSmartCard != 1) {
            _uiState.update {
                it.copy(
                    settleNotice = WaterUnpaidNotice.Unsettled(
                        amount = detail.payPrice,
                        orderNo = detail.orderNo,
                        reason = WaterUnpaidReason.NOT_SMART_CARD,
                        serverMessage = detail.payModeName,
                    )
                )
            }
            return null
        }

        val orderNo = detail.orderNo
        if (orderNo.isNullOrBlank()) return null
        if (!canAttemptSettle(detail.orderId, force)) return null

        return settleAndReload(orderNo, detail.payPrice, detail.orderId)
    }

    /**
     * 真正调用补缴接口，并把结果写进 [UjingWaterUiState.settleNotice]。
     *
     * 失败分两类，因为它们对用户的意义完全不同：
     * - [isRetriableSettleFailure]（`1021` 一卡通扣款没成功，多为余额不足）：**不记入
     *   [settledOrders]**，只设退避；用户充值后重进页面会自动再试一次。
     * - 其它业务拒绝：同样不记入，但文案按「服务端拒绝」处理。
     *
     * @return 成功后重新拉到的详情；失败返回 null。
     */
    private suspend fun settleAndReload(
        orderNo: String,
        amount: Double,
        orderId: Long,
    ): WbuUjingClient.WaterOrderDetail? {
        return try {
            ujingClient.settleWaterOrder(orderNo)
            settledOrders.add(orderId)
            settleBackoffUntil.remove(orderId)
            Log.i(TAG, "已补缴订单 $orderNo（¥$amount）")
            _uiState.update {
                it.copy(settleNotice = WaterUnpaidNotice.Settled(amount = amount, orderNo = orderNo))
            }
            // 重新拉一次：完成页要显示真实的扣费金额与「已支付」
            runCatching { ujingClient.fetchOrderDetail(orderId) }.getOrNull()
        } catch (e: WbuUjingClient.UjingApiException) {
            // 「请勿重复请求」= 服务端正在处理，不是拒绝：查一下结果再说
            if (e.code == WbuUjingClient.CODE_DUPLICATE_REQUEST) {
                Log.i(TAG, "补缴返回「请勿重复请求」，改为复查订单状态: $orderNo")
                val fresh = recheckAfterDuplicate(orderId)
                if (fresh != null && !UjingPayFlag.from(fresh.payFlag).isOutstanding(fresh.payPrice)) {
                    // 其实已经结清了（上一次请求成功，只是回包慢）
                    settledOrders.add(orderId)
                    settleBackoffUntil.remove(orderId)
                    _uiState.update {
                        it.copy(settleNotice = WaterUnpaidNotice.Settled(amount = amount, orderNo = orderNo))
                    }
                    return fresh
                }
                // 还没结清：说「正在处理」，不说「失败」。不进退避 ——
                // 这单可能下一刻就变成已支付，用户再点一次也无害。
                _uiState.update {
                    it.copy(
                        settleNotice = WaterUnpaidNotice.Unsettled(
                            amount = amount,
                            orderNo = orderNo,
                            reason = WaterUnpaidReason.PENDING,
                        )
                    )
                }
                return null
            }

            val funds = isRetriableSettleFailure(e)
            settleBackoffUntil[orderId] = System.currentTimeMillis() + settleRetryBackoffMs
            Log.w(
                TAG,
                if (funds) "补缴失败（疑似余额不足，可重试）: $orderNo / ${e.code} ${e.message}"
                else "补缴被服务端拒绝: $orderNo / ${e.code} ${e.message}"
            )
            _uiState.update {
                it.copy(
                    settleNotice = WaterUnpaidNotice.Unsettled(
                        amount = amount,
                        orderNo = orderNo,
                        reason = if (funds) WaterUnpaidReason.INSUFFICIENT_FUNDS
                        else WaterUnpaidReason.SERVER_REJECTED,
                        serverMessage = e.message,
                    )
                )
            }
            null
        } catch (e: Exception) {
            settleBackoffUntil[orderId] = System.currentTimeMillis() + settleRetryBackoffMs
            Log.w(TAG, "补缴请求失败: $orderNo", e)
            _uiState.update {
                it.copy(
                    settleNotice = WaterUnpaidNotice.Unsettled(
                        amount = amount,
                        orderNo = orderNo,
                        reason = WaterUnpaidReason.REQUEST_FAILED,
                        serverMessage = e.message,
                    )
                )
            }
            null
        }
    }

    /**
     * 把账号名下**最近一笔欠费**结掉（如果它是一卡通直扣、且开关开着）。
     *
     * 为什么是账号级、不按设备：U净 一个账号同时只允许一个出水点，欠着一单就会挡住
     * 下一次下单 —— 用户站在 3 号楼的机器前，也该把昨天在南 B 欠的那一单结掉。
     *
     * 全程**不抛异常、不阻塞取水**：查不到列表、网络不通、服务端拒绝，都只是记一条
     * [WaterUnpaidNotice]，把决定权留给用户。返回是否真的结清了一单。
     *
     * **失败不写进 [settledOrders]**：一卡通扣款失败多为余额不足，用户充完钱重进页面
     * 必须还能再自动试一次（只受 [settleBackoffUntil] 的短时退避约束）。
     *
     * @param force 忽略开关与退避（用户点「先结清再试」时用）。
     */
    private suspend fun settleOutstandingOrder(force: Boolean = false): Boolean {
        val app = getApplication<Application>()
        if (!force && !WbuAuthTransport.isAutoSettleWaterUnpaidEnabled(app)) return false

        val outstanding = try {
            val orders = ujingClient.listWaterOrders(skip = 0, limit = 20)
            pickOutstandingOrder(
                orders = orders,
                payFlag = { it.payFlag },
                payPrice = { it.payPrice },
            )
        } catch (e: Exception) {
            // 查不到就当没有：这一步是锦上添花，不能因为它让取水失败
            Log.w(TAG, "查询欠费订单失败，跳过自动补缴", e)
            return false
        } ?: return false

        val orderKey = outstanding.orderId
        if (!canAttemptSettle(orderKey, force)) {
            Log.d(TAG, "欠费单 $orderKey 已结清或在退避窗口内，跳过补缴")
            return false
        }

        // 列表项没有 waterForceSmartCard，得再查一次详情才知道能不能一卡通直扣
        val detail = try {
            ujingClient.fetchOrderDetail(outstanding.orderId)
        } catch (e: Exception) {
            Log.w(TAG, "查询欠费单详情失败: $orderKey", e)
            _uiState.update {
                it.copy(
                    settleNotice = WaterUnpaidNotice.Unsettled(
                        amount = outstanding.payPrice,
                        orderNo = outstanding.orderNo,
                        reason = WaterUnpaidReason.REQUEST_FAILED,
                        serverMessage = e.message,
                    )
                )
            }
            return false
        }

        // 详情可能显示它其实已经结清了（列表有延迟）：那就什么都不做
        if (!UjingPayFlag.from(detail.payFlag).isOutstanding(detail.payPrice)) return false

        if (detail.waterForceSmartCard != 1) {
            Log.i(TAG, "欠费单 $orderKey 不是一卡通直扣（waterForceSmartCard=${detail.waterForceSmartCard}），不自动补缴")
            _uiState.update {
                it.copy(
                    settleNotice = WaterUnpaidNotice.Unsettled(
                        amount = detail.payPrice,
                        orderNo = detail.orderNo,
                        reason = WaterUnpaidReason.NOT_SMART_CARD,
                        serverMessage = detail.payModeName,
                    )
                )
            }
            return false
        }

        val orderNo = detail.orderNo
        if (orderNo.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    settleNotice = WaterUnpaidNotice.Unsettled(
                        amount = detail.payPrice,
                        orderNo = null,
                        reason = WaterUnpaidReason.NO_ORDER_NO,
                    )
                )
            }
            return false
        }

        return settleAndReload(orderNo, detail.payPrice, detail.orderId) != null
    }

    /**
     * 启动饮水机扫码出水全流程。
     */
    fun start(cd: String) {
        val trimmed = cd.trim()
        if (trimmed.isBlank()) return

        // 0. **同一个页面被别的页面盖过又回来**（去充值 / 看账单 / 补登录）：
        //    ViewModel 还活着，订单号、进度、结算结果都还在手里。这时唯一正确的做法是
        //    把原状态接回来 —— 重走 [executeFlow] 会**再下一单**，而用户只是去充了个值。
        //
        //    刻意**不在这里自动补缴**：用户只是去充值，回来该看到的是原来那一页
        //    （欠费提示 + 「立即补缴」），由他自己决定什么时候扣这笔钱。
        //    悄悄动账比多按一次按钮更糟 —— 那是在用户没同意的时间点花他的钱。
        if (processedEntry && currentCd == trimmed) {
            Log.i(TAG, "start: 从覆盖页返回，复用现有状态 stage=${_uiState.value.stage::class.simpleName}")
            return
        }

        // 1. 同一 ViewModel 实例防重入：若正在加载或正在出水中，防止重复触发
        if (hasStarted && (_uiState.value.stage is UjingWaterUiStage.Active || _uiState.value.stage is UjingWaterUiStage.Loading)) {
            Log.d(TAG, "start ignored: already active in stage=${_uiState.value.stage::class.simpleName}")
            return
        }

        // 2. 检查跨重建/跨实例的近期会话记录，防止后台切回重建时重复出水（仅针对未完成的活跃订单）
        val cached = lastSession
        val now = System.currentTimeMillis()
        if (cached != null && cached.cd == trimmed && (now - cached.timestamp < SESSION_VALID_DURATION_MS)) {
            if (cached.stage is UjingWaterUiStage.Active) {
                Log.i(TAG, "Resuming active water session for cd=$trimmed, orderId=${cached.orderId}")
                hasStarted = true
                processedEntry = true
                currentCd = trimmed
                _uiState.update { it.copy(cd = trimmed, stage = cached.stage, needLogin = false) }
                startStatusWatch(trimmed, cached.orderId, cached.subject)
                return
            }
        }

        hasStarted = true
        processedEntry = true
        autoTunnelTried = false
        currentCd = trimmed
        _uiState.update {
            it.copy(cd = currentCd, stage = UjingWaterUiStage.Splash, needLogin = false, settleNotice = null)
        }

        viewModelScope.launch {
            // 开场过场动画保持 800ms
            delay(800)
            executeFlow(currentCd)
        }
    }

    /**
     * 重新开始打水（用户在取水完成页再次触碰 NFC 或点击继续出水）。
     */
    fun restart(cd: String) {
        clearSession()
        stopStatusWatch()
        hasStarted = false
        // 「再接一杯」明确是新的一单：必须让 [start] 走完整流程，而不是当成「从覆盖页返回」
        processedEntry = false
        autoTunnelTried = false
        // 「再接一杯」是新一轮：上一轮失败的退避窗口不该继续拦着这单
        settleBackoffUntil.clear()
        _uiState.update {
            it.copy(stage = UjingWaterUiStage.Splash, needLogin = false, settleNotice = null)
        }
        start(cd)
    }

    private fun executeFlow(cd: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. 获取平台有效的 access_token
                _uiState.update { it.copy(stage = UjingWaterUiStage.Loading(getApplication<Application>().getString(R.string.status_verifying_campus_card))) }
                val platformToken = runCatching { cardClient.ensureValidAccessToken() }.getOrElse { e ->
                    if (e is WbuSessionExpiredException || e.message?.contains("失效") == true) {
                        // 会话失效：用保存的密码静默重登一次，再换一次平台票（都发生在下单之前）
                        val failure = silentLogin(
                            app = getApplication(),
                            stageText = getApplication<Application>().getString(R.string.status_login_saved_credentials)
                        )
                        if (failure != null) {
                            showLoginOrError(failure)
                            return@launch
                        }
                        runCatching { cardClient.ensureValidAccessToken() }.getOrElse { secondErr ->
                            Log.w(TAG, "Re-fetch platform token after silent login failed", secondErr)
                            showLoginOrError(
                                WbuFailureDetector.fromThrowable(secondErr, AccessLayer.CampusDirect)
                            )
                            return@launch
                        }
                    } else {
                        throw e
                    }
                }

                // 2. 建立 U净 会话（换票）
                _uiState.update { it.copy(stage = UjingWaterUiStage.Loading(getApplication<Application>().getString(R.string.status_connecting_ujing))) }
                ujingClient.connect(platformToken, appId = WbuUjingClient.WATER_APP_ID)

                // 3. 绑定取水点
                _uiState.update { it.copy(stage = UjingWaterUiStage.Loading(getApplication<Application>().getString(R.string.status_binding_water_point))) }
                val subject = ujingClient.bindWaterPoint(cd)

                // 4. 先清掉账号名下的欠费单，否则下一步下单会被服务端直接拒掉（2040224）
                _uiState.update { it.copy(stage = UjingWaterUiStage.Loading(getApplication<Application>().getString(R.string.status_checking_unpaid_water))) }
                settleOutstandingOrder()

                // 5. 下单出水
                _uiState.update { it.copy(stage = UjingWaterUiStage.Loading(getApplication<Application>().getString(R.string.status_dispensing_water))) }
                val order = try {
                    ujingClient.dispenseWater(cd)
                } catch (e: WbuUjingClient.UjingApiException) {
                    // 服务端说「有订单未结束」：可能是刚才没查到的欠费单（列表延迟 / 刚产生），
                    // 也可能是真有一单在出水。
                    //
                    // 这里用 force = false：**刚在步骤 4 试过就别再打**。连打只会拿到
                    // 6020208「请勿重复请求」，把一次「余额不足」的提示覆盖成一句看不懂的话。
                    // 退避窗口（30s）正好挡住这种自打自脸，又不影响用户充完值重进页面。
                    if (!e.blocksDispensing()) throw e
                    Log.i(TAG, "下单被未结束订单挡住（${e.code}），尝试补缴后重试一次")
                    _uiState.update { it.copy(stage = UjingWaterUiStage.Loading(getApplication<Application>().getString(R.string.status_settling_unpaid_water))) }
                    if (!settleOutstandingOrder(force = false)) throw e
                    ujingClient.dispenseWater(cd)
                }

                // 真正用上了：记进校园设备管理（下单成功才算，失败尝试不留痕）
                recordDeviceUse(subject)

                // 6. 进入出水进行中状态并开启监听
                val activeStage = UjingWaterUiStage.Active(
                    subject = subject,
                    orderResult = order,
                    detail = null,
                    mqttConnected = false
                )
                lastSession = WaterSession(
                    cd = cd,
                    orderId = order.orderId,
                    subject = subject,
                    stage = activeStage,
                    timestamp = System.currentTimeMillis()
                )
                _uiState.update { it.copy(stage = activeStage) }

                startStatusWatch(cd, order.orderId, subject)

            } catch (e: WbuSessionExpiredException) {
                _uiState.update { it.copy(needLogin = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Water flow error", e)
                val app = getApplication<Application>()

                // 「有订单没结束 / 有未支付订单」不是网络问题，重试一百次也还是同一句话：
                // 单独成一类，页面给的是「去结清」而不是「重试」。
                val unpaidBlocks = (e as? WbuUjingClient.UjingApiException)?.blocksDispensing() == true
                if (unpaidBlocks) {
                    _uiState.update {
                        it.copy(
                            stage = UjingWaterUiStage.Error(
                                message = e.message ?: app.getString(R.string.err_unpaid_water_order),
                                canRetry = false,
                                unpaidBlocksOrder = true,
                            )
                        )
                    }
                    return@launch
                }

                // 只有「确定是通道问题」的异常才升级成结构化失败：U净 自己的业务异常
                // （设备离线、订单被占…）多数也是 IOException，不能一并当成「网络不通」，
                // 否则会让用户去连 WiFi / 开 WebVPN，而他其实什么都不用做。
                val failure = when (e) {
                    is WafBlockedException -> WbuFailureDetector.fromThrowable(e, AccessLayer.UnifiedAuth)
                    is NoNetworkException -> WbuFailureDetector.fromThrowable(e, AccessLayer.Service)
                    else -> null
                }
                val message = failure?.let { accessFailureText(app, it) }
                    ?: userFacingMessage(app, e)
                    ?: app.getString(R.string.err_dispensing_failed)

                // 自动改道：直连这条道明确不通（被 WAF 拦 / 一个响应都没拿到），而且 WebVPN 凭据
                // 现成可用时，直接切隧道重跑一次，不必让用户在错误页上再点一下。只自动试一次；
                // 凭据不可用就别切 —— 真切过去只会把用户卡在门户登录页。
                val directTrouble = failure?.directChannelTrouble == true
                if (directTrouble &&
                    !WbuAuthTransport.getIdsViaWebVpn(app) &&
                    !autoTunnelTried &&
                    WbuAuthTransport.isTunnelReady(app)
                ) {
                    autoTunnelTried = true
                    WbuAuthTransport.setIdsViaWebVpn(app, true)
                    Log.i(TAG, "直连不通且 WebVPN 凭据可用，自动改道重试一次：$e")
                    clearSession()
                    hasStarted = true
                    executeFlow(cd)
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        stage = UjingWaterUiStage.Error(
                            message = message,
                            offerWebVpnOnce = failure
                                ?.shouldOfferWebVpnOnce(WbuAuthTransport.getIdsViaWebVpn(app)) == true
                        )
                    )
                }
            }
        }
    }

    /**
     * 结合 MQTT 长连接与 HTTP 轮询监听订单状态。
     */
    private fun startStatusWatch(
        cd: String,
        orderId: Long,
        subject: WbuUjingClient.WaterServiceSubject
    ) {
        stopStatusWatch()

        // 1. 启动 MQTT 长连接
        val mqtt = UjingMqttClient(
            deviceId = cd,
            targetOrderId = orderId,
            scope = viewModelScope
        ).also { mqttClient = it }

        viewModelScope.launch {
            mqtt.connectionState.collect { connected ->
                _uiState.update { current ->
                    if (current.stage is UjingWaterUiStage.Active) {
                        current.copy(stage = current.stage.copy(mqttConnected = connected))
                    } else current
                }
            }
        }

        viewModelScope.launch {
            mqtt.statusFlow.collect { mqttStatus ->
                // MQTT 推送到达，立即更新本地状态
                handleStatusUpdate(mqttStatus.status, orderId, subject)
            }
        }

        mqtt.start()

        // 2. 启动 HTTP 轮询兜底（每 2.5 秒拉取详情，补全出水量与扣费）
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            var consecutiveFails = 0
            while (isActive) {
                try {
                    val detail = ujingClient.fetchOrderDetail(orderId)
                    consecutiveFails = 0
                    updateWithDetail(detail, subject)

                    if (isDispenseDone(detail)) {
                        Log.i(TAG, "Dispense done via poll: ${detail.orderStatusName} / payFlag=${detail.payFlag}")
                        // 水取完了但钱没扣到（62 结账异常 / 65 支付失败结束 / 卡在 30 + 支付失败）：
                        // 先把账结掉再显示完成页，否则用户下次下单会被 2040224 挡住却不知道为什么。
                        val settleResult = settleOrderIfUnpaid(detail)
                        markOrderFinished(subject, settleResult ?: detail)
                        break
                    }
                } catch (e: Exception) {
                    consecutiveFails++
                    Log.w(TAG, "Poll order detail fail ($consecutiveFails)", e)
                    // 一直拉不到就放慢重试，但不彻底停：页面还开着，网络一恢复状态就能补上，
                    // 不会卡在「正在取水」上再也不动（页面关掉后协程自己结束）
                    if (consecutiveFails > 8) {
                        delay(10_000)
                        continue
                    }
                }
                delay(2500)
            }
        }
    }

    private fun markOrderFinished(
        subject: WbuUjingClient.WaterServiceSubject,
        detail: WbuUjingClient.WaterOrderDetail
    ) {
        stopStatusWatch()
        clearSession()
        hasStarted = false
        val finishedStage = UjingWaterUiStage.Finished(subject, detail)
        _uiState.update { it.copy(stage = finishedStage) }
    }

    private fun handleStatusUpdate(
        status: Int,
        orderId: Long,
        subject: WbuUjingClient.WaterServiceSubject
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val detail = runCatching { ujingClient.fetchOrderDetail(orderId) }.getOrNull()
            if (detail != null) {
                updateWithDetail(detail, subject)
                if (isDispenseDone(detail)) {
                    Log.i(TAG, "Dispense done via MQTT/fetch: ${detail.orderStatusName} / payFlag=${detail.payFlag}")
                    // 与轮询那条路同口径：还没结清就先结掉，再进完成页
                    val settleResult = settleOrderIfUnpaid(detail)
                    markOrderFinished(subject, settleResult ?: detail)
                }
            } else {
                // 如果一时拉不到详情，根据 status 简易更新
                _uiState.update { current ->
                    if (current.stage is UjingWaterUiStage.Active) {
                        val updatedDetail = current.stage.detail?.copy(
                            orderStatus = status,
                            orderStatusName = WbuUjingClient.orderStatusName(status),
                            isTerminal = status in WbuUjingClient.TERMINAL_STATUS
                        ) ?: WbuUjingClient.WaterOrderDetail(
                            orderId = orderId,
                            orderNo = current.stage.orderResult.orderNo,
                            orderStatus = status,
                            orderStatusName = WbuUjingClient.orderStatusName(status),
                            storeName = subject.storeName,
                            deviceNo = null,
                            orderTypeName = getApplication<Application>().getString(R.string.label_scan_qr_water),
                            payTypeName = getApplication<Application>().getString(R.string.label_campus_card_quick_pay),
                            hotWaterMl = 0,
                            warmWaterMl = 0,
                            payPrice = 0.0,
                            isTerminal = status in WbuUjingClient.TERMINAL_STATUS
                        )
                        current.copy(stage = current.stage.copy(detail = updatedDetail))
                    } else current
                }
            }
        }
    }

    private fun updateWithDetail(
        detail: WbuUjingClient.WaterOrderDetail,
        subject: WbuUjingClient.WaterServiceSubject
    ) {
        _uiState.update { current ->
            if (current.stage is UjingWaterUiStage.Active) {
                current.copy(stage = current.stage.copy(detail = detail))
            } else current
        }
        // 订单详情里有店铺名（currentInfo 接口不给，只有这里给）：
        // 顺手补进使用记录，列表标题就能显示「南B-11」而不是一串设备码。
        // 注意：detail.deviceNo 是**取水点水表号**，同层楼的几台机器会完全一样（实测两条不同 cd
        // 都是 000006），不能当机号用 —— 饮水机的机号取 cd 尾号，由 CampusDeviceEntry 自己算。
        // 只补店铺名 + 主体名，且同一单只补一次（轮询每 2.5 秒一次，不必反复整表重写）。
        if (detail.storeName != null && !namesFilledForOrder(detail.orderId)) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    deviceHubRepository.fillNames(
                        kind = CampusDeviceKind.WATER,
                        key = currentCd,
                        storeName = detail.storeName,
                        subjectName = subject.subjectName,
                    )
                }.onFailure { Log.w(TAG, "补全饮水机名称失败", it) }
            }
        }
    }

    private fun stopStatusWatch() {
        pollJob?.cancel()
        pollJob = null
        mqttClient?.stop()
        mqttClient = null
    }

    /**
     * 把这次取水记进「校园设备管理」（下单成功那一刻调用）。
     *
     * 恢复旧订单（[start] 的缓存分支）不走这里 —— 那不是一次新的使用，不该重复计数。
     * 记录失败只打日志：使用记录是锦上添花，不能把取水流程带崩。
     */
    private fun recordDeviceUse(subject: WbuUjingClient.WaterServiceSubject) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                deviceHubRepository.record(
                    CampusDeviceEntry(
                        kind = CampusDeviceKind.WATER,
                        key = currentCd,
                        storeName = subject.storeName,
                        subjectName = subject.subjectName,
                    )
                )
            }.onFailure { Log.w(TAG, "记录饮水机使用失败", it) }
        }
    }

    /**
     * 用保存的密码静默登录统一认证（缺 WebVPN 门禁密码 / 短信验证码 / 图形校验时就地弹小窗）。
     *
     * 调用时机：先让 `WbuCampusCardClient.ensureValidAccessToken()` 复用仍然有效的 **CASTGC**（免密换票），
     * 只有它缺失 / 失效时才走到这里。
     *
     * 实现统一交给 [silentUnifiedAuthLogin]：小窗走进程级通道（不再占用本页状态），失败原因也是结构化的，
     * 于是「只有确实要用户补新凭据才弹登录 Sheet」这条规则在本页同样成立。
     */
    private suspend fun silentLogin(app: Application, stageText: String): AccessFailure? {
        _uiState.update { it.copy(stage = UjingWaterUiStage.Loading(stageText)) }
        // 是否经 WebVPN 只由「统一认证经过WebVPN」决定：没开就完全不牵扯 WebVPN
        // （本项目里 /w/ 是直连服务，本来也不需要校园网）
        return silentUnifiedAuthLogin(
            context = app,
            flowTag = "UJING_WATER",
            viaWebVpn = WbuAuthTransport.getIdsViaWebVpn(app),
            // 本机没存密码就别弹小窗问：直接回落到登录 Sheet（本页的老行为）
            onlyWithSavedPassword = true
        )
    }

    /**
     * 静默登录失败后的收尾。
     *
     * **只有**「会话失效 / 凭据被拒」才把用户请去登录 Sheet；用户取消小窗、网络不通、服务端异常
     * 都只留一条可重试的说明 —— 以前这里任何失败都会弹 Sheet，用户在小窗上点个取消也会被顶一脸登录框。
     */
    private fun showLoginOrError(failure: AccessFailure) {
        val app = getApplication<Application>()
        if (failure.needsRelogin) {
            _uiState.update { it.copy(needLogin = true) }
            return
        }
        _uiState.update {
            it.copy(
                needLogin = false,
                stage = UjingWaterUiStage.Error(
                    message = accessFailureText(app, failure)
                        ?: app.getString(R.string.err_need_unified_auth_session),
                    // 被 WAF 拦 / 直连不通才给「改用 WebVPN」；网络真断开时换通道也没用
                    offerWebVpnOnce = failure.shouldOfferWebVpnOnce(WbuAuthTransport.getIdsViaWebVpn(app))
                )
            )
        }
    }

    fun onLoginSuccess() {
        _uiState.update { it.copy(needLogin = false) }
        if (currentCd.isNotBlank()) {
            executeFlow(currentCd)
        }
    }

    fun onLoginDismissed() {
        _uiState.update {
            it.copy(
                needLogin = false,
                stage = UjingWaterUiStage.Error(getApplication<Application>().getString(R.string.err_login_required_water), canRetry = false)
            )
        }
    }

    fun retry() {
        if (currentCd.isNotBlank()) {
            clearSession()
            hasStarted = true
            executeFlow(currentCd)
        }
    }

    /**
     * 「先结清欠费再试」（错误页上那个按钮）。
     *
     * 与 [retry] 的区别：它**强制**再结一次（忽略开关与退避窗口），
     * 因为用户点它就是在说「我确实想付这笔钱」。开关关着时也照办 ——
     * 用户主动点的是「结清」，不是「帮我自动结清」。
     */
    fun settleThenRetry() {
        if (currentCd.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(stage = UjingWaterUiStage.Loading(getApplication<Application>().getString(R.string.status_settling_unpaid_water)))
            }
            val settled = settleOutstandingOrder(force = true)
            if (settled) {
                retry()
            } else {
                // 没结成：留在错误页，但把「为什么没结成」说清楚（settleNotice 已写好）
                _uiState.update {
                    it.copy(
                        stage = UjingWaterUiStage.Error(
                            message = getApplication<Application>().getString(R.string.err_unpaid_water_order),
                            canRetry = true,
                            unpaidBlocksOrder = true,
                        )
                    )
                }
            }
        }
    }

    /**
     * 「重新支付」（完成页上那个按钮）。
     *
     * 水已经取完了，这里只补钱：成功就把完成页的详情换成结清后的（金额 / 已支付），
     * 失败就更新 [UjingWaterUiState.settleNotice] 让页面改口。
     */
    fun settleCurrentOrder() {
        val stage = _uiState.value.stage as? UjingWaterUiStage.Finished ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // 用户主动点「重新支付」：忽略去重与开关，直接再试一次
            val updated = settleOrderIfUnpaid(stage.detail, force = true)
            if (updated != null) {
                _uiState.update {
                    if (it.stage is UjingWaterUiStage.Finished) {
                        it.copy(stage = UjingWaterUiStage.Finished(stage.subject, updated))
                    } else {
                        it
                    }
                }
            }
        }
    }

    /**
     * 「去充值」：跳到一卡通充值页（平台 `card-recharge` / `feeitemid=410`）。
     *
     * 只在一卡通扣款失败（余额不足）时才出现在界面上 —— 那正是唯一需要它的场景。
     * 走与门户旧链接改道同一个 `appCode`，学校改配置也不会失效；解析不出应用表时
     * 由 `campusCardDeepLinkFallback` 兜底。
     */
    fun rechargeCampusCard(navBridge: com.xingheyuzhuan.shiguangschedule.NavBridge) {
        navBridge.navigate(
            com.xingheyuzhuan.shiguangschedule.Destination.WebApp(
                appId = com.shiro.classflow.data.model.wbu.WebAppId.CAMPUS_CARD.name,
                campusCardAppCode = "card-recharge",
                campusCardDeepLinkFallback = "/campus-card/?name=cardRecharge&feeitemid=410&hasinstallConfigForm=1",
                // 这一趟只为充值：用户点「完成」回到平台首页时自动收起容器，
                // 把他送回取水页（页面还活着，欠费提示与「立即补缴」都还在）
                endsWithTask = true,
            )
        )
    }

    /**
     * 这个 ViewModel 是否已经为本次进入跑过流程（见 [processedEntry]）。
     *
     * 页面用它在**页面被重建**时区分两种截然不同的情况：
     * - 为 true：只是被别的页面盖过又回来，订单还在，原样显示即可；
     * - 为 false：进程已经死过一次，手里没有任何订单信息，别装出「正在取水」的样子。
     */
    fun hasProcessedEntry(): Boolean = processedEntry

    /**
     * 「改用 WebVPN 再试」。
     *
     * 取水页没有「仅本次走隧道」的临时开关：统一认证走不走 WebVPN 只由设置里的那一个偏好决定
     * （`WbuAuthTransport.getIdsViaWebVpn`），换票链路每次请求都实时读它。所以这里**写入**该偏好再重试。
     * 也就是说，用户点的这句「改用 WebVPN」是持久的，设置页里看得见、也随时能关掉 ——
     * 与其造一个只在取水页生效、退出就消失的隐藏状态，不如让开关保持单一来源。
     *
     * 只影响 id 系主机（统一认证 / 一卡通换票），`yktfwpt`、`opac` 本来就是直连服务，不受牵连。
     */
    fun retryWithWebVpnOnce() {
        val app = getApplication<Application>()
        WbuAuthTransport.setIdsViaWebVpn(app, true)
        Log.i(TAG, "用户选择改用 WebVPN：已开启统一认证走 WebVPN，重试取水")
        retry()
    }

    override fun onCleared() {
        super.onCleared()
        stopStatusWatch()
    }
}
