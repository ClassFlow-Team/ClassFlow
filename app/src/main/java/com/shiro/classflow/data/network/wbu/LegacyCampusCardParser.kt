package com.shiro.classflow.data.network.wbu

import com.shiro.classflow.data.model.wbu.LegacyBillCategory
import com.shiro.classflow.data.model.wbu.LegacyCampusCardEntry
import com.shiro.classflow.data.model.wbu.LegacyCampusCardPage
import com.shiro.classflow.data.model.wbu.LegacyYearTotals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 旧一卡通平台页面 / AJAX 响应的纯解析逻辑。
 *
 * 单独抽出来是为了能直接做 JVM 单测：这些响应格式没有任何契约文档，字段类型还混着
 * 数字与字符串（`TRANAMT` 是数字、分类 `value` 却是字符串），一旦学校改版，先在这里失效。
 */
internal object LegacyCampusCardParser {

    /** 旧平台每页固定 15 条。 */
    const val PAGE_SIZE = 15

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** `/ias/prelogin` 自动提交表单里的两个隐藏字段。 */
    data class PreLoginForm(
        val continueUrl: String,
        val ssoTicketId: String
    )

    /** 解析 `/ias/prelogin` 的自动提交表单；字段不全时返回 null。 */
    fun parsePreLoginForm(html: String): PreLoginForm? {
        val continueUrl = inputValue(html, "continueurl") ?: return null
        val ssoTicketId = inputValue(html, "ssoticketid") ?: return null
        if (continueUrl.isBlank() || ssoTicketId.isBlank()) return null
        return PreLoginForm(continueUrl, ssoTicketId)
    }

    /** 从账单 / 流水页解析 `var saccount = '...'`。 */
    fun parseAccount(html: String): String? =
        Regex("var\\s+saccount\\s*=\\s*'([^']+)'", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * 解析 `GetMyBill` / `GetPersonTrjn` / `getMonthbillBytype` 的分页响应。
     *
     * 注意：旧平台即使 `rows` 有数据，`issucceed` 也实测为 `false`，所以这里完全不看它，
     * 只以 `rows` 是否存在为准。
     */
    fun parseEntries(body: String): LegacyCampusCardPage {
        val root = parseElement(body)
        require(root is JsonObject || root is JsonArray) {
            "旧一卡通响应不是 JSON 对象 / 数组"
        }
        val rows = when (root) {
            is JsonObject -> root.arrayOrNull("rows") ?: JsonArray(emptyList())
            is JsonArray -> root
        }
        val entries = rows.mapIndexedNotNull { index, element ->
            (element as? JsonObject)?.let { parseEntry(it, index) }
        }
        val total = when (root) {
            is JsonObject -> root.intOrNull("total") ?: entries.size
            else -> entries.size
        }
        return LegacyCampusCardPage(
            rows = entries,
            total = total,
            hasMore = rows.size >= PAGE_SIZE
        )
    }

    /** 解析 `getamtBypaytype`；响应可能是数组，也可能包一层 `rows` / `data`。 */
    fun parseCategories(body: String): List<LegacyBillCategory> {
        val root = parseElement(body)
        require(root is JsonObject || root is JsonArray) {
            "旧一卡通月账单响应不是 JSON 对象 / 数组"
        }
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root.arrayOrNull("rows") ?: root.arrayOrNull("data") ?: JsonArray(emptyList())
        }
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val code = item.stringOrNull("code")
                ?: item.stringOrNull("CODE")
                ?: item.stringOrNull("typeid")
                ?: return@mapNotNull null
            val name = item.stringOrNull("name")
                ?: item.stringOrNull("NAME")
                ?: code
            val amount = item.doubleOrNull("value")
                ?: item.doubleOrNull("amount")
                ?: 0.0
            LegacyBillCategory(code = code.trim(), name = name.trim(), amount = amount)
        }
    }

    /** 解析 `EveryMonthPay`。缺失字段按空列表 / 0 处理，避免整页失败。 */
    fun parseYearTotals(body: String): LegacyYearTotals {
        val root = parseElement(body) as? JsonObject
            ?: throw IllegalArgumentException("旧一卡通年度汇总响应不是 JSON 对象")
        val months = (root.arrayOrNull("resmonth") ?: root.arrayOrNull("months") ?: JsonArray(emptyList()))
            .mapNotNull { it.stringOrNull() }
        val amounts = (root.arrayOrNull("amt") ?: root.arrayOrNull("amounts") ?: JsonArray(emptyList()))
            .mapNotNull { it.doubleOrNull() }
        val max = root.doubleOrNull("maxamt")
            ?: root.doubleOrNull("max")
            ?: amounts.maxOrNull()
            ?: 0.0
        return LegacyYearTotals(months = months, amounts = amounts, max = max)
    }

    // ------------------- 内部工具 -------------------

    private fun parseElement(body: String): JsonElement {
        val cleaned = body.trim().removePrefix("\uFEFF")
        return json.parseToJsonElement(cleaned)
    }

    private fun parseEntry(item: JsonObject, index: Int): LegacyCampusCardEntry {
        val occurredAt = item.stringOrNull("OCCTIME")?.trim().orEmpty()
        val serial = item.stringOrNull("JNUM")?.trim()
            ?: item.stringOrNull("RO")?.trim()
            ?: (index + 1).toString()
        // 同一秒同一商户可能存在多笔，行号兜底保证列表 key 不重复
        val rowIndex = item.stringOrNull("RO")?.trim() ?: (index + 1).toString()
        return LegacyCampusCardEntry(
            id = "$occurredAt|$serial|$rowIndex",
            occurredAt = occurredAt,
            effectAt = item.stringOrNull("EFFECTDATE")?.trim()?.takeIf { it.isNotEmpty() },
            weekday = item.stringOrNull("XQ")?.trim()?.takeIf { it.isNotEmpty() },
            merchant = item.stringOrNull("MERCNAME")?.trim().orEmpty(),
            amount = item.doubleOrNull("TRANAMT") ?: 0.0,
            transName = item.stringOrNull("TRANNAME")?.trim().orEmpty(),
            cardBalance = item.doubleOrNull("CARDBAL"),
            description = item.stringOrNull("JDESC")?.trim()?.takeIf { it.isNotEmpty() },
            serial = serial,
            account = item.stringOrNull("MACCOUNT")?.trim()?.takeIf { it.isNotEmpty() },
            transCode = item.stringOrNull("TRANCODE")?.trim()?.takeIf { it.isNotEmpty() },
            accountType = item.stringOrNull("ACCTYPENAME")?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun inputValue(html: String, name: String): String? {
        val tag = Regex(
            "<input[^>]*name\\s*=\\s*[\"']$name[\"'][^>]*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.value ?: return null
        return Regex("value\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(tag)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun JsonObject.arrayOrNull(key: String): JsonArray? {
        val value = this[key]
        return if (value is JsonArray) value else null
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val value = this[key]
        return if (value is JsonPrimitive && value !is JsonNull) value.content else null
    }

    private fun JsonObject.intOrNull(key: String): Int? = stringOrNull(key)?.toIntOrNull()

    private fun JsonObject.doubleOrNull(key: String): Double? = stringOrNull(key)?.toDoubleOrNull()

    private fun JsonElement.stringOrNull(): String? =
        if (this is JsonPrimitive && this !is JsonNull) content else null

    private fun JsonElement.doubleOrNull(): Double? = stringOrNull()?.toDoubleOrNull()
}
