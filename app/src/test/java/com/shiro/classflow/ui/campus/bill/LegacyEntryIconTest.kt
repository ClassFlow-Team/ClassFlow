package com.shiro.classflow.ui.campus.bill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 关键词图标分类。
 *
 * 用例全部来自旧平台**真实流水**（拉了 7 页共 86 条），不是凭空构造的字符串。
 */
class LegacyEntryIconTest {

    @Test
    fun `U净洗衣机归到 U净`() {
        assertEquals(
            LegacyEntryIconKind.UJING,
            legacyEntryIconKind("U净洗衣机直饮水吹风机", "电子账户消费")
        )
    }

    @Test
    fun `各食堂都归到餐饮`() {
        listOf("南区食堂", "北区食堂", "马影河食堂", "南北区食堂京膳").forEach { merchant ->
            assertEquals(
                "食堂 $merchant 应该归到餐饮",
                LegacyEntryIconKind.DINING,
                legacyEntryIconKind(merchant, "持卡人消费")
            )
        }
    }

    @Test
    fun `微信只靠交易类型也能认出来`() {
        // 真实数据里这类记录的 MERCNAME 是空串，只看商户名会漏成普通消费
        assertEquals(
            LegacyEntryIconKind.WECHAT,
            legacyEntryIconKind("", "微信支付转账")
        )
    }

    @Test
    fun `网费与电控`() {
        assertEquals(LegacyEntryIconKind.NETWORK, legacyEntryIconKind("网费", "电子账户消费"))
        assertEquals(LegacyEntryIconKind.ELECTRIC, legacyEntryIconKind("电控缴费", "电子账户消费"))
    }

    @Test
    fun `设置类关键词`() {
        listOf("持卡人开户", "持卡人修改密码", "账户合并").forEach { merchant ->
            assertEquals(
                "$merchant 应该归到设置类",
                LegacyEntryIconKind.ACCOUNT_SETTING,
                legacyEntryIconKind(merchant, "")
            )
        }
    }

    @Test
    fun `持卡人消费不能被当成设置类`() {
        // 关键回归：「持卡人」不是关键词，「持卡人开户」才是。
        // 13/86 笔都是「持卡人消费」，误判会让大量普通消费显示成齿轮图标。
        assertEquals(
            LegacyEntryIconKind.DEFAULT,
            legacyEntryIconKind("", "持卡人消费")
        )
        // 带商户名的持卡人消费按商户归类（食堂），但**绝不能**变成设置类
        assertNotEquals(
            LegacyEntryIconKind.ACCOUNT_SETTING,
            legacyEntryIconKind("南区食堂", "持卡人消费")
        )
    }

    @Test
    fun `扫码支付与电子账户消费是兜底`() {
        assertEquals(LegacyEntryIconKind.DEFAULT, legacyEntryIconKind("", "扫码支付"))
        assertEquals(LegacyEntryIconKind.DEFAULT, legacyEntryIconKind("", "电子账户消费"))
        assertEquals(LegacyEntryIconKind.DEFAULT, legacyEntryIconKind("", "持卡人消费"))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(LegacyEntryIconKind.UJING, legacyEntryIconKind("ujing washer", ""))
        assertEquals(LegacyEntryIconKind.UJING, legacyEntryIconKind("UJING washer", ""))
    }
}
