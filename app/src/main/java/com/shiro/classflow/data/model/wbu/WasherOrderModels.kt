package com.shiro.classflow.data.model.wbu

import kotlinx.serialization.Serializable

/**
 * 洗衣机订单所处的阶段（U净 `orders` 系列接口返回的 `status`）。
 *
 * 编码取自实测 HAR（2026-10-16 南B-12 一单完整流程）与 washer-h5 自身的判断：
 * - `10` 已预约（下单后、支付前）；
 * - `20` / `22` 已支付，等待启动；
 * - `21` / `23` / `25` / `26` / `27` / `29` / `30` / `35` / `40` 进行中
 *   （`30` 启动中、`35` 筒自洁、`40` 运行中，其余是 H5 的 `isInProgress` 集合）；
 * - `50`~`59` 已结束（H5 把这些码当作终态，不再轮询）；
 * - `60` 及以上是故障 / 提前结束 / 支付失败等异常终态。
 *
 * 不认识的码一律 [UNKNOWN]：宁可显示「状态未知」也不要把异常单当成洗完。
 */
@Serializable
enum class WasherOrderPhase {
    /** 已预约，尚未支付。 */
    RESERVED,

    /** 已支付，等待用户按「启动洗衣」。 */
    PAID,

    /** 正在洗（含启动中、筒自洁）。 */
    RUNNING,

    /** 正常结束。 */
    DONE,

    /** 故障 / 提前结束 / 支付失败等异常终态。 */
    ERROR,

    /** 服务端给了没见过的码。 */
    UNKNOWN;

    /** 需要盯着看的单（还会变化）。 */
    val isActive: Boolean
        get() = this == RESERVED || this == PAID || this == RUNNING

    /** 已经结束的单（不会再变化，可以提醒）。 */
    val isTerminal: Boolean
        get() = this == DONE || this == ERROR

    companion object {
        fun fromStatus(status: Int): WasherOrderPhase = when (status) {
            10 -> RESERVED
            20, 22 -> PAID
            21, 23, 25, 26, 27, 29, 30, 35, 40 -> RUNNING
            in 50..59 -> DONE
            in 60..99 -> ERROR
            else -> UNKNOWN
        }
    }
}

/**
 * 一条洗衣机订单（列表项与详情共用）。
 *
 * `orders/running` / `orders/history` 只给概要字段，`orders/{id}/detail` 才带
 * [cycle]、[workTimeMinutes]、[remainSeconds]；缺字段时保持默认值，由 UI 决定怎么退化展示。
 */
@Serializable
data class WasherOrderSummary(
    val orderId: Long,
    /** U净 内部设备 ID（MQTT 订阅用的就是它，不是扫码原文里的 uuid）。 */
    val deviceId: String? = null,
    /** 纯机号（`000001`）。 */
    val deviceNo: String? = null,
    val deviceTypeId: Int = 0,
    /** 店铺名（`南B-12`）。 */
    val storeName: String? = null,
    /** 程序描述（`普通洗 | 60度高温洗`）。 */
    val cycle: String? = null,
    val status: Int = -1,
    /** 服务端原话（`运行中` / `已支付` / `已预约`），优先于本地映射。 */
    val statusRemark: String? = null,
    /** 实付金额（元）。 */
    val payPrice: Double = 0.0,
    /**
     * 程序总时长（分钟，服务端 `workTime` 原值）。
     *
     * 仅用于画进度条。H5 从不读这个字段，单位没有官方保证，所以进度条只在它能
     * 给出合理比例时才画（见 [progress]）。
     */
    val workTimeMinutes: Int = 0,
    /** 拉取那一刻的剩余秒数（服务端 `remainTime` 原值，未加显示补偿）。 */
    val remainSeconds: Int = 0,
    /** 下单时间（ISO8601 原文，仅用于展示）。 */
    val createAt: String? = null,
) {
    companion object {
        /**
         * 官方 H5 在显示层给 `remainTime` 加的 1 分钟。
         *
         * 取自 washer-h5 订单详情：`<countDown type="hm" :time="info.remainTime*1000 + 60000">`。
         */
        const val DISPLAY_GRACE_SECONDS = 60

        /**
         * 「金额未知」的哨兵值。
         *
         * 实测 `orders/history` 的列表项**不带金额字段**（只有 `payFlag`），金额只在
         * `orders/{id}/detail` 里。把缺失当成 0 会让账单显示「0.00 元」——用户会以为
         * 那单真的免费，而官方 H5 显示的是实付金额。用负数标记「还不知道，去补详情」。
         */
        const val UNKNOWN_PRICE = -1.0
    }

    /** 金额是否已知（见 [UNKNOWN_PRICE]）。 */
    val hasKnownPrice: Boolean get() = payPrice >= 0.0

    val phase: WasherOrderPhase get() = WasherOrderPhase.fromStatus(status)

    /**
     * 机号短形式：与 `CampusDeviceEntry.shortDeviceNo` 同一套规则
     * （`000001` → `01`，`000123` → `123`），非纯数字返回 null。
     */
    val shortDeviceNo: String?
        get() = deviceNo?.takeIf { it.isNotBlank() && it.matches(Regex("\\d+")) }?.let { no ->
            val value = no.toLongOrNull() ?: return@let no
            if (value in 0..99) value.toString().padStart(2, '0') else value.toString()
        }

    /** 列表 / 卡片标题：「店铺-机号」，缺一就退到有的那一半。 */
    val machineTitle: String?
        get() {
            val store = storeName?.takeIf { it.isNotBlank() }
            val no = shortDeviceNo
            return when {
                store != null && no != null -> "$store-$no"
                store != null -> store
                no != null -> no
                else -> null
            }
        }

    /**
     * 按「拉取时刻 + 剩余秒数」推算当前剩余秒数。
     *
     * 不信任本机时钟的绝对时间：只做「现在 - 拉取时刻」的差值，配合服务端下发的
     * [remainSeconds]，即使手机时间不准也只会偏一个固定量，不会把 40 分钟的单算成负数。
     *
     * 这里**不加**官方 H5 的那 60 秒显示补偿（见 [displayRemainingSeconds]），
     * 因为闹钟与通知必须按真实结束时间算，提前 1 分钟就弹「洗好了」是错的。
     */
    fun remainingSeconds(nowMillis: Long, fetchedAtMillis: Long): Int {
        if (phase != WasherOrderPhase.RUNNING) return 0
        val elapsed = ((nowMillis - fetchedAtMillis) / 1000L).coerceAtLeast(0L)
        return (remainSeconds.toLong() - elapsed).coerceAtLeast(0L).toInt()
    }

    /**
     * 界面上显示的剩余秒数 —— 对齐官方 washer-h5 的口径。
     *
     * 官方订单详情页写的是 `countDown time = remainTime + 60`，即它显示的数字比
     * 服务端真实的 `remainTime` 多 1 分钟。用户会拿原生页和 H5 对照，两边必须一致，
     * 所以显示层同样加这 60 秒；**只影响显示**，不影响闹钟（见 [remainingSeconds]）。
     */
    fun displayRemainingSeconds(nowMillis: Long, fetchedAtMillis: Long): Int {
        if (phase != WasherOrderPhase.RUNNING) return 0
        return remainingSeconds(nowMillis, fetchedAtMillis) + DISPLAY_GRACE_SECONDS
    }

    /**
     * 进度 0f~1f；拿不到总时长时返回 0（UI 据此决定不画进度条）。
     *
     * `workTime` 的单位在实测响应里是分钟，但 H5 从不读它，单位没有官方保证。
     * 实测还会出现 `remainTime` 略大于 `workTime*60` 的情况（服务端把剩余时间
     * 算得比标称时长多一点），所以这里把基准取成两者的较大值 —— 这样进度仍然
     * 从 0 附近开始走，而不是负成负数或被夹成一根永远满格的假进度条。
     */
    fun progress(nowMillis: Long, fetchedAtMillis: Long): Float {
        val stated = workTimeMinutes * 60
        if (stated <= 0) return 0f
        val baseline = maxOf(stated, remainSeconds)
        if (baseline <= 0) return 0f
        val current = remainingSeconds(nowMillis, fetchedAtMillis)
        return ((baseline - current).toFloat() / baseline).coerceIn(0f, 1f)
    }

    /** 这条单是不是「刚洗完」——只对正常结束生效。 */
    val isDone: Boolean get() = phase == WasherOrderPhase.DONE

    /** 这条单是不是异常结束。 */
    val isFailed: Boolean get() = phase == WasherOrderPhase.ERROR
}

/**
 * 订单 + 快照时间。
 *
 * 落库时把「拉取那一刻」一起存下来，页面重新打开后倒计时能接着走，
 * 不需要每次进页面都打一次接口。
 */
@Serializable
data class WasherOrderSnapshot(
    val order: WasherOrderSummary,
    /** 拉取时刻（毫秒）。 */
    val fetchedAt: Long,
)
