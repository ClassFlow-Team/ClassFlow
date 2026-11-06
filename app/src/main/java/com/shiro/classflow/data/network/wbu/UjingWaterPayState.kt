package com.shiro.classflow.data.network.wbu

/**
 * U净 饮水订单的「付款状态」（`payFlag`）。
 *
 * 与 `orderStatus` 是**两层完全不同的东西**，别混：
 * - `orderStatus`（0/10/30/50/…）说「这单水取到哪一步了」；
 * - `payFlag` 说「这单钱结清了没有」。
 *
 * 所以一单可以「取水正常完成」却仍然欠费（`orderStatus = 50` + `payFlag = UNPAID`），
 * 也可以「支付失败结束」（`orderStatus = 65`）。欠费不结清，下一次下单会被服务端挡住
 * （`createWaterOrder` 回 `1532`「您有支付宝订单未结账，请先结账！」）。
 *
 * 枚举取自 U净 `water-h5` 线上产物（`vendor-ujing-libs` 的 `payFlag` 常量表），
 * 官方订单列表也是按它判断「待支付」的。
 */
enum class UjingPayFlag {
    /** 0：未支付。 */
    UNPAID,

    /** 1：已支付。 */
    PAID,

    /** 2：已退款。 */
    REFUNDED,

    /** 3：支付中（银行/渠道还没回结果）。 */
    PAYING,

    /** 4：支付失败。 */
    PAY_FAILED,

    /** 5：退款中。 */
    REFUNDING,

    /** 6：退款失败。 */
    REFUND_FAILED,

    /** 服务端给了个我们不认识的码，或压根没给。 */
    UNKNOWN;

    companion object {
        fun from(value: Int?): UjingPayFlag = when (value) {
            0 -> UNPAID
            1 -> PAID
            2 -> REFUNDED
            3 -> PAYING
            4 -> PAY_FAILED
            5 -> REFUNDING
            6 -> REFUND_FAILED
            else -> UNKNOWN
        }
    }
}

/**
 * 这一单是否还欠着钱（官方订单列表的「待支付」口径）。
 *
 * 判据与 U净 `orderList` 页面逐字一致：
 * `payFlag ∈ {UNPAID, PAYING, PAY_FAILED} && payPrice != 0`。
 *
 * 两个细节都有意保留：
 * - **`PAYING` 也算**：官方把它和未支付摆在一起给「去支付」按钮。真到补缴时
 *   服务端会自己判断（重复付不会扣两次），我们只要不把它当成「已结清」跳过。
 * - **`payPrice == 0` 不算**：0 元单（计量口径问题、免单、全额优惠）永远结不掉，
 *   算成欠费就会每次进页面都去「补缴」一次，纯属骚扰。
 */
fun UjingPayFlag.isOutstanding(payPrice: Double): Boolean = when (this) {
    UjingPayFlag.UNPAID, UjingPayFlag.PAYING, UjingPayFlag.PAY_FAILED -> payPrice != 0.0
    else -> false
}

/**
 * 这一单能不能**自动**扣款结清。
 *
 * 只有「一卡通直接扣」（`waterForceSmartCard == 1`）才允许自动结算：U净 官方未支付页
 * 对这类订单走的是一卡通渠道（`channel = campusOneCardPay`），请求一发出钱就扣了，
 * 不需要拉起任何第三方 App、也不需要用户在手机上再确认一次。
 *
 * 其余支付方式（微信 / 支付宝 / 云闪付 / 电子账户）一律**不自动**动账：
 * - 云闪付要 `upsdk`、微信/支付宝要各自的钱包，自建容器里根本起不来；
 * - 电子账户余额扣款动的是用户钱包，没有明确确认不该替他做主。
 * 这类订单只提示、不代扣。
 */
fun canAutoSettleOrder(
    payFlag: Int?,
    payPrice: Double,
    waterForceSmartCard: Int?,
): Boolean = UjingPayFlag.from(payFlag).isOutstanding(payPrice) && waterForceSmartCard == 1

/**
 * 从订单列表里挑出「最该先结清」的那一单。
 *
 * 欠费是**账号级**的（U净 一个账号同时只允许一个出水点，欠着一单就会挡住下一次下单），
 * 所以刻意**不按设备过滤** —— 用户站在 3 号楼的机器前，也该把昨天在南 B 欠的那一单结掉。
 *
 * [orders] 需按「最近在前」传入（`waterOrderList` 的默认顺序就是它）；这里只做稳定挑选，
 * 不重新排序，避免拿字符串时间解析去猜顺序。
 */
fun <T> pickOutstandingOrder(
    orders: List<T>,
    payFlag: (T) -> Int?,
    payPrice: (T) -> Double,
): T? = orders.firstOrNull { UjingPayFlag.from(payFlag(it)).isOutstanding(payPrice(it)) }
