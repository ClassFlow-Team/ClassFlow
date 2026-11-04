package com.shiro.classflow.data.model.wbu

/**
 * 旧一卡通平台（`yktyd.wbu.edu.cn:81`）的账单 / 流水视图模式。
 *
 * - [BILL]：「我的账单」`Report/GetMyBill`，按月份分组，适合看消费概况；
 * - [FLOW]：「流水查询」`Report/GetPersonTrjn`，连续流水，多带账户类型（电子账户 / 校园卡）。
 */
enum class LegacyCampusCardMode {
    BILL,
    FLOW
}

/**
 * 旧一卡通平台的一条交易记录。
 *
 * 金额单位是**元**（不是分），[amount] 保留原始符号：负数消费、正数收入；
 * [cardBalance] 是该笔交易后的余额。商户 / 交易类型字段在旧平台里带尾部空格，解析时已经 trim。
 */
data class LegacyCampusCardEntry(
    /** 稳定唯一键：`OCCTIME + JNUM + 行号`，用于 Compose 列表与详情定位。 */
    val id: String,
    /** 交易时间 `yyyy-MM-dd HH:mm:ss`（`OCCTIME`）。 */
    val occurredAt: String,
    /** 入账时间（`EFFECTDATE`），可能为空。 */
    val effectAt: String? = null,
    /** 星期（`XQ`，旧平台给的是英文全称）。 */
    val weekday: String? = null,
    /** 商户名称（`MERCNAME`）。 */
    val merchant: String,
    /** 交易金额，单位元，带符号。 */
    val amount: Double,
    /** 交易类型（`TRANNAME`，如「电子账户消费」「扫码支付」）。 */
    val transName: String,
    /** 交易后余额（`CARDBAL`）。 */
    val cardBalance: Double? = null,
    /** 交易说明（`JDESC`），可能包含二维码原文。 */
    val description: String? = null,
    /** 交易流水号（`JNUM`）。 */
    val serial: String,
    /** 交易账号（`MACCOUNT`）。 */
    val account: String? = null,
    /** 交易码（`TRANCODE`），月账单分类里用的就是它。 */
    val transCode: String? = null,
    /** 账户类型（`ACCTYPENAME`，只有流水接口返回）。 */
    val accountType: String? = null
) {
    /** 收入（正数）。 */
    val isIncome: Boolean get() = amount > 0

    /** `yyyy-MM-dd`。 */
    val date: String get() = occurredAt.take(10)

    /** `HH:mm`。 */
    val time: String get() = occurredAt.drop(11).take(5)

    /** `yyyy-MM`，用于按月份分组。 */
    val month: String get() = occurredAt.take(7)
}

/** 一页账单 / 流水。旧平台每页固定 15 条。 */
data class LegacyCampusCardPage(
    val rows: List<LegacyCampusCardEntry>,
    val total: Int,
    val hasMore: Boolean
)

/** 月账单里的一个分类（`getamtBypaytype` 的 `code` 即 `TRANCODE`）。 */
data class LegacyBillCategory(
    val code: String,
    val name: String,
    val amount: Double
)

/** 年度 12 个月的消费汇总（`EveryMonthPay`）。 */
data class LegacyYearTotals(
    val months: List<String>,
    val amounts: List<Double>,
    val max: Double
)
