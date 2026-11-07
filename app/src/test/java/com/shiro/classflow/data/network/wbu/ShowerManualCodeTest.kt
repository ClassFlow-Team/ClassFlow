package com.shiro.classflow.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动输入解析单测。
 *
 * 判型只回答两件事：这条输入**是否已经写明系统**（Pinned），或者**系统要不要用户勾选**
 * （Ambiguous，附带一个按位数的默认勾选）。服务端认不认设备不在这一层 —— 2-3 栋的
 * `getDevicesType` 实测不校验设备存在，所以这里绝不假装能替用户判断系统。
 */
class ShowerManualCodeTest {

    private fun pinned(raw: String): ShowerManualCode.Result.Pinned {
        val result = ShowerManualCode.parse(raw)
        assertTrue("expected Pinned for <$raw>, got $result", result is ShowerManualCode.Result.Pinned)
        return result as ShowerManualCode.Result.Pinned
    }

    private fun ambiguous(raw: String): ShowerManualCode.Result.Ambiguous {
        val result = ShowerManualCode.parse(raw)
        assertTrue("expected Ambiguous for <$raw>, got $result", result is ShowerManualCode.Result.Ambiguous)
        return result as ShowerManualCode.Result.Ambiguous
    }

    // ───────────────── 输入本身写明系统的情形 ─────────────────

    @Test
    fun parsesYktDeepLink() {
        val candidate = pinned("https://h.example.com/s/y/10101").candidate
        assertEquals(CampusShowerLink.SYSTEM_YKT, candidate.system)
        assertEquals("10101", candidate.code)
        assertNull(candidate.port)
    }

    @Test
    fun parsesLifeDeepLinkWithPort() {
        val candidate = pinned("https://h.example.com/s/l/17010737/2").candidate
        assertEquals(CampusShowerLink.SYSTEM_LIFE, candidate.system)
        assertEquals("17010737", candidate.code)
        assertEquals("2", candidate.port)
    }

    @Test
    fun parsesImeiPortSeparator() {
        val candidate = pinned("17010737\$#\$2").candidate
        assertEquals(CampusShowerLink.SYSTEM_LIFE, candidate.system)
        assertEquals("17010737", candidate.code)
        assertEquals("2", candidate.port)
    }

    @Test
    fun parsesSchoolShowerLink() {
        val candidate = pinned("http://yktxyyy1.wbu.edu.cn:50040?id=17010737").candidate
        assertEquals(CampusShowerLink.SYSTEM_LIFE, candidate.system)
        assertEquals("17010737", candidate.code)
        // 主机上的 :50040 不是控水器端口
        assertNull(candidate.port)
    }

    @Test
    fun parsesVendorLink() {
        val candidate = pinned("http://4gsk.shuibiao51.com?id=1234567890").candidate
        assertEquals("1234567890", candidate.code)
    }

    @Test
    fun parsesBareLongCodeAsYkt() {
        // 1 栋扫码裸码：第 8~12 位是机号
        val candidate = pinned("ABCDEFGH10101").candidate
        assertEquals(CampusShowerLink.SYSTEM_YKT, candidate.system)
        assertEquals("10101", candidate.code)
    }

    @Test
    fun rejectsOtherUrlEvenWithId() {
        // 一卡通平台 / CAS 上的 ?id= 不是设备码：不能因为「有 id」就当 2-3 栋
        assertTrue(
            ShowerManualCode.parse("http://yktfwpt.wbu.edu.cn/plat/index?id=17010737")
                is ShowerManualCode.Result.Unparsed
        )
        assertTrue(
            ShowerManualCode.parse("https://www.wbu.edu.cn/news?id=17010737")
                is ShowerManualCode.Result.Unparsed
        )
    }

    // ───────────────── 只有数字：由用户勾选系统 ─────────────────

    @Test
    fun shortDigitsSuggestYkt() {
        val plan = ambiguous("10101")
        assertEquals("10101", plan.digits)
        assertNull(plan.port)
        assertEquals(CampusShowerLink.SYSTEM_YKT, plan.suggestedSystem)
    }

    @Test
    fun longDigitsSuggestLife() {
        val plan = ambiguous("17010737")
        assertEquals(CampusShowerLink.SYSTEM_LIFE, plan.suggestedSystem)
    }

    @Test
    fun veryLongDigitsSuggestLife() {
        // 水表设备号可能超出 Int 范围，按位数一律建议 2-3 栋
        assertEquals(
            CampusShowerLink.SYSTEM_LIFE,
            ambiguous("202612345678901").suggestedSystem
        )
    }

    @Test
    fun leadingZeroDigitsSuggestYktAndNormalizeWhenResolved() {
        val plan = ambiguous("0101")
        assertEquals(CampusShowerLink.SYSTEM_YKT, plan.suggestedSystem)
        // 落成候选时机号按数字值归一（与 /s/y/ 深链一致）
        val candidate = ShowerManualCode.resolve(plan, CampusShowerLink.SYSTEM_YKT)
        assertEquals("101", candidate?.code)
    }

    @Test
    fun numberWithSlashPort() {
        val plan = ambiguous("17010737/2")
        assertEquals("17010737", plan.digits)
        assertEquals("2", plan.port)
        assertEquals(CampusShowerLink.SYSTEM_LIFE, plan.suggestedSystem)
    }

    @Test
    fun numberWithSpacePort() {
        val plan = ambiguous("17010737 2")
        assertEquals("2", plan.port)
    }

    // ───────────────── 勾选的系统收不下这串输入 ─────────────────

    @Test
    fun yktRejectsMoreThanFiveDigits() {
        // 1 栋机号最多 5 位：勾了 1 栋却输 8 位数字，不该送去撞接口
        val plan = ambiguous("17010737")
        assertNull(ShowerManualCode.resolve(plan, CampusShowerLink.SYSTEM_YKT))
    }

    @Test
    fun yktRejectsPort() {
        // 1 栋没有端口概念
        val plan = ambiguous("17010737/2")
        assertNull(ShowerManualCode.resolve(plan, CampusShowerLink.SYSTEM_YKT))
    }

    @Test
    fun lifeAcceptsDigitsAndPort() {
        val plan = ambiguous("17010737/2")
        val candidate = ShowerManualCode.resolve(plan, CampusShowerLink.SYSTEM_LIFE)
        assertEquals(CampusShowerLink.SYSTEM_LIFE, candidate?.system)
        assertEquals("17010737", candidate?.code)
        assertEquals("2", candidate?.port)
    }

    @Test
    fun lifeAcceptsShortDigitsAsIs() {
        // 2-3 栋设备号原样保留（可能带前导零）
        val plan = ambiguous("010101")
        val candidate = ShowerManualCode.resolve(plan, CampusShowerLink.SYSTEM_LIFE)
        assertEquals("010101", candidate?.code)
    }

    // ───────────────── 无效输入 ─────────────────

    @Test
    fun rejectsBlankAndWhitespaceOnly() {
        assertTrue(ShowerManualCode.parse("") is ShowerManualCode.Result.Unparsed)
        assertTrue(ShowerManualCode.parse("   ") is ShowerManualCode.Result.Unparsed)
    }

    @Test
    fun rejectsGarbage() {
        assertTrue(ShowerManualCode.parse("abc") is ShowerManualCode.Result.Unparsed)
        assertTrue(ShowerManualCode.parse("17010737 2 3") is ShowerManualCode.Result.Unparsed)
    }

    @Test
    fun rejectsAllZeroDigits() {
        assertTrue(ShowerManualCode.parse("00000") is ShowerManualCode.Result.Unparsed)
    }

    @Test
    fun rejectsTooLongInput() {
        assertTrue(ShowerManualCode.parse("1".repeat(300)) is ShowerManualCode.Result.Unparsed)
    }

    @Test
    fun rejectsTooLongPort() {
        // 端口上限 8 位（与 CampusShowerEntryResolver.buildLifeServiceRaw 同口径）
        assertTrue(ShowerManualCode.parse("17010737/123456789") is ShowerManualCode.Result.Unparsed)
    }

    @Test
    fun rejectsNonNumericPort() {
        assertTrue(ShowerManualCode.parse("17010737/ab") is ShowerManualCode.Result.Unparsed)
    }

    @Test
    fun rejectsThreePartSeparator() {
        assertTrue(
            ShowerManualCode.parse("17010737\$#\$2\$#\$3") is ShowerManualCode.Result.Unparsed
        )
    }
}
