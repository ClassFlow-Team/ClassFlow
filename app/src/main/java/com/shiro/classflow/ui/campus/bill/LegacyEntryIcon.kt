package com.shiro.classflow.ui.campus.bill

/**
 * 交易行的关键词图标分类。
 *
 * 判据来自旧平台真实数据（`MERCNAME` / `TRANNAME`），不是凭空猜的：
 * `U净洗衣机直饮水吹风机`、`南区食堂`、`北区食堂`、`马影河食堂`、`南北区食堂京膳`、
 * `网费`、`电控缴费`，以及 `MERCNAME` 为空、只能靠 `TRANNAME` 认出来的 `微信支付转账`。
 */
internal enum class LegacyEntryIconKind {
    /** U净洗衣机 / 直饮水 / 吹风机。 */
    UJING,

    /** 食堂 / 餐厅 / 京膳 / 饭。 */
    DINING,

    /** 微信支付转账（商户名通常为空）。 */
    WECHAT,

    /** 校园网网费。 */
    NETWORK,

    /** 电控缴费 / 电费。 */
    ELECTRIC,

    /** 持卡人开户 / 修改密码 / 账户合并这类「设置」性质的操作。 */
    ACCOUNT_SETTING,

    /** 兜底：普通消费 / 充值 / 退款。 */
    DEFAULT
}

/**
 * 关键词 → 图标分类。**纯函数**，不依赖 Android，便于单测。
 *
 * 两个刻意的取舍：
 * 1. **不能**用「持卡人」当关键词 —— `持卡人消费` 是最普通的消费（13/86 笔），
 *    而 `持卡人开户` 才是设置类。只认 `开户` / `修改密码` / `账户合并` 这几个完整词。
 * 2. 商户名与交易类型**一起**匹配：`微信支付转账` 的 `MERCNAME` 是空串，
 *    只看商户名会把它漏成普通消费。
 */
internal fun legacyEntryIconKind(merchant: String, transName: String): LegacyEntryIconKind {
    val haystack = "$merchant $transName".lowercase()

    // 设置类排在最前：`持卡人开户` 也含「持卡人」，但绝不能被下面的规则抢走
    if (haystack.anyContains(ACCOUNT_SETTING_KEYWORDS)) return LegacyEntryIconKind.ACCOUNT_SETTING
    if (haystack.anyContains(UJING_KEYWORDS)) return LegacyEntryIconKind.UJING
    if (haystack.anyContains(DINING_KEYWORDS)) return LegacyEntryIconKind.DINING
    if (haystack.anyContains(WECHAT_KEYWORDS)) return LegacyEntryIconKind.WECHAT
    if (haystack.anyContains(NETWORK_KEYWORDS)) return LegacyEntryIconKind.NETWORK
    if (haystack.anyContains(ELECTRIC_KEYWORDS)) return LegacyEntryIconKind.ELECTRIC
    return LegacyEntryIconKind.DEFAULT
}

private val ACCOUNT_SETTING_KEYWORDS = listOf("开户", "修改密码", "账户合并", "改密", "密码重置")
private val UJING_KEYWORDS = listOf("u净", "ujing")
private val DINING_KEYWORDS = listOf("食堂", "餐厅", "京膳", "饭")
private val WECHAT_KEYWORDS = listOf("微信")
private val NETWORK_KEYWORDS = listOf("网费", "校园网")
private val ELECTRIC_KEYWORDS = listOf("电控", "电费")

private fun String.anyContains(keywords: List<String>): Boolean =
    keywords.any { this.contains(it) }
