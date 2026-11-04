package com.shiro.classflow.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧一卡通账单解析单测。
 *
 * 这些响应没有契约文档，字段类型混着数字与字符串；这里钉住线上实测格式，
 * 学校改版时能第一时间在解析层失败，而不是让页面静默显示空列表。
 */
class LegacyCampusCardParserTest {

    @Test
    fun `解析 prelogin 自动提交表单`() {
        val html = """
            <html><body>
            <form id="loginForm" action="" method="post">
                <input type="hidden" name="errorcode" value="1" />
                <input type="hidden" name="continueurl" value="http://yktyd.wbu.edu.cn:81/cassyno/index" />
                <input type="hidden" name="ssoticketid" value="abc123" />
            </form>
            </body></html>
        """.trimIndent()

        val form = LegacyCampusCardParser.parsePreLoginForm(html)

        assertEquals("http://yktyd.wbu.edu.cn:81/cassyno/index", form?.continueUrl)
        assertEquals("abc123", form?.ssoTicketId)
    }

    @Test
    fun `解析 prelogin 表单缺少字段时返回 null`() {
        assertNull(LegacyCampusCardParser.parsePreLoginForm("<html></html>"))
    }

    @Test
    fun `从账单页解析 saccount`() {
        val html = "var saccount = '27849';\nif (saccount != '') {}"
        assertEquals("27849", LegacyCampusCardParser.parseAccount(html))
        assertNull(LegacyCampusCardParser.parseAccount("<html>登录</html>"))
    }

    @Test
    fun `解析账单条目并 trim 尾部空格`() {
        val body = """
            {
              "issucceed": false,
              "total": 92,
              "rows": [
                {
                  "RO": 1,
                  "OCCTIME": "2026-11-01 18:36:18",
                  "EFFECTDATE": "2026-11-01 18:36:16",
                  "XQ": "sunday   ",
                  "MERCNAME": "U净洗衣机直饮水吹风机                   ",
                  "TRANAMT": -0.38,
                  "TRANNAME": "电子账户消费                            ",
                  "CARDBAL": 2.97,
                  "JDESC": "1000056-000-0 null",
                  "JNUM": 1415321,
                  "MACCOUNT": 1000056,
                  "TRANCODE": "94"
                }
              ]
            }
        """.trimIndent()

        val page = LegacyCampusCardParser.parseEntries(body)

        assertEquals(92, page.total)
        assertEquals(1, page.rows.size)
        assertFalse(page.hasMore)
        val entry = page.rows.single()
        assertEquals("U净洗衣机直饮水吹风机", entry.merchant)
        assertEquals("电子账户消费", entry.transName)
        assertEquals(-0.38, entry.amount, 0.0001)
        assertEquals(2.97, entry.cardBalance!!, 0.0001)
        assertEquals("2026-11-01", entry.date)
        assertEquals("18:36", entry.time)
        assertEquals("2026-11", entry.month)
        assertFalse(entry.isIncome)
        assertEquals("94", entry.transCode)
        assertEquals("1000056", entry.account)
    }

    @Test
    fun `解析流水条目带账户类型`() {
        val body = """
            {
              "total": 98,
              "rows": [
                {
                  "RO": 16,
                  "OCCTIME": "2026-10-08 21:17:58",
                  "MERCNAME": "U净洗衣机直饮水吹风机",
                  "TRANAMT": -0.51,
                  "TRANNAME": "电子账户消费",
                  "TRANCODE": "94",
                  "CARDBAL": 4.73,
                  "JNUM": 823748,
                  "MACCOUNT": 1000056,
                  "SYSCODE": 54,
                  "POSCODE": 0,
                  "CMONEY": 4.73,
                  "ACCTYPENAME": "电子账户"
                }
              ]
            }
        """.trimIndent()

        val entry = LegacyCampusCardParser.parseEntries(body).rows.single()
        assertEquals("电子账户", entry.accountType)
    }

    @Test
    fun `收入条目保留正号语义`() {
        val body = """{"total":1,"rows":[{"OCCTIME":"2026-10-01 08:00:00","MERCNAME":"充值","TRANAMT":100,"TRANNAME":"充值","JNUM":1}]}"""
        val entry = LegacyCampusCardParser.parseEntries(body).rows.single()
        assertEquals(100.0, entry.amount, 0.0001)
        assertTrue(entry.isIncome)
        assertNull(entry.cardBalance)
    }

    @Test
    fun `整页时还有更多`() {
        val rows = (1..15).joinToString(",") { index ->
            """{"RO":$index,"OCCTIME":"2026-10-01 10:00:00","MERCNAME":"m","TRANAMT":-1,"TRANNAME":"t","JNUM":$index}"""
        }
        val page = LegacyCampusCardParser.parseEntries("""{"total":20,"rows":[$rows]}""")
        assertEquals(15, page.rows.size)
        assertTrue(page.hasMore)
    }

    @Test
    fun `不满一页时没有更多`() {
        val rows = (1..14).joinToString(",") { index ->
            """{"RO":$index,"OCCTIME":"2026-10-01 10:00:00","MERCNAME":"m","TRANAMT":-1,"TRANNAME":"t","JNUM":$index}"""
        }
        val page = LegacyCampusCardParser.parseEntries("""{"total":14,"rows":[$rows]}""")
        assertFalse(page.hasMore)
    }

    @Test
    fun `空 rows 是正常空页`() {
        val page = LegacyCampusCardParser.parseEntries("""{"issucceed":false,"total":0,"rows":[]}""")
        assertTrue(page.rows.isEmpty())
        assertEquals(0, page.total)
        assertFalse(page.hasMore)
    }

    @Test
    fun `解析月账单分类字符串金额`() {
        val body = """[{"name":"电子账户消费","value":"106.72","code":"94"},{"name":"扫码支付","value":"18.00","code":"99"}]"""
        val categories = LegacyCampusCardParser.parseCategories(body)

        assertEquals(2, categories.size)
        assertEquals("94", categories[0].code)
        assertEquals("电子账户消费", categories[0].name)
        assertEquals(106.72, categories[0].amount, 0.0001)
        assertEquals(18.00, categories[1].amount, 0.0001)
    }

    @Test
    fun `解析年度月度汇总`() {
        val body = """{"resmonth":["01月","02月"],"amt":["0","20.57"],"maxamt":124.72}"""
        val totals = LegacyCampusCardParser.parseYearTotals(body)

        assertEquals(listOf("01月", "02月"), totals.months)
        assertEquals(listOf(0.0, 20.57), totals.amounts)
        assertEquals(124.72, totals.max, 0.0001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非 JSON 响应直接失败而不是静默空列表`() {
        LegacyCampusCardParser.parseEntries("<html><title>错误</title></html>")
    }
}
