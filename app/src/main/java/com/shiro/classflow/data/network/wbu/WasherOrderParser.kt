package com.shiro.classflow.data.network.wbu

import com.shiro.classflow.data.model.wbu.WasherOrderSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * U净 洗衣机订单解析（`orders/running`、`orders/history`、`orders/{id}/detail`）。
 *
 * 用 kotlinx.serialization 而不是 `org.json`，是为了让这些规则能在**普通 JVM 单测**里跑：
 * `org.json` 在单元测试里是空实现（没开 `returnDefaultValues`），而订单状态的判定
 * 恰恰是最需要钉住的部分 —— 把「异常单当成洗完」推给用户的代价太高。
 */
object WasherOrderParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析 `data` 里的一条订单。
     *
     * 兼容实测中同时出现的两种写法：`status` 是**字符串**（`"40"`，详情接口）
     * 或**数字**（`40`，历史列表），以及 `orderId` / `id` 两个字段名。
     */
    fun parseOrder(obj: JsonObject): WasherOrderSummary? {
        val orderId = obj.longOrNull("orderId") ?: obj.longOrNull("id") ?: return null
        if (orderId <= 0L) return null
        val status = obj.intOrNull("status") ?: -1
        return WasherOrderSummary(
            orderId = orderId,
            deviceId = obj.stringOrNull("deviceId"),
            deviceNo = obj.stringOrNull("deviceNo"),
            deviceTypeId = obj.intOrNull("deviceTypeId") ?: 0,
            storeName = obj.stringOrNull("storeName"),
            cycle = obj.stringOrNull("cycle"),
            status = status,
            statusRemark = obj.stringOrNull("statusRemark"),
            payPrice = obj.doubleOrNull("payPrice")
                ?: obj.doubleOrNull("payment")
                // 缺失时用 **-1** 表示「未知」，而不是 0.0。
                // 实测 `orders/history` 的列表项**不带任何金额字段**（只有 payFlag），
                // 落到 0.0 会让账单显示「0.00 元」，比不显示金额更误导。
                ?: WasherOrderSummary.UNKNOWN_PRICE,
            workTimeMinutes = obj.intOrNull("workTime") ?: 0,
            remainSeconds = obj.intOrNull("remainTime") ?: 0,
            createAt = obj.stringOrNull("createAt"),
        )
    }

    /**
     * 解析一个「订单数组」响应体（`{"code":0,"data":[...]}`）。
     *
     * 也接受 `data` 直接是单个对象的情况：`orders/running` 在某些账号下可能只回一条。
     */
    fun parseOrderList(body: String): List<WasherOrderSummary> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val data = root["data"] ?: return emptyList()
        val array = runCatching { data.jsonArray }.getOrNull()
        if (array != null) {
            return array.mapNotNull { element ->
                runCatching { parseOrder(element.jsonObject) }.getOrNull()
            }
        }
        val single = runCatching { data.jsonObject }.getOrNull() ?: return emptyList()
        return listOfNotNull(runCatching { parseOrder(single) }.getOrNull())
    }

    /** 解析单条详情响应体（`{"code":0,"data":{...}}`）。 */
    fun parseOrderDetail(body: String): WasherOrderSummary? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val data = runCatching { root["data"]?.jsonObject }.getOrNull() ?: return null
        return runCatching { parseOrder(data) }.getOrNull()
    }

    /**
     * 判断「这一单是不是刚刚从运行中变成结束」。
     *
     * [previousStatus] 为 null（第一次看到这一单）时不提醒：用户可能是在订单已经洗完
     * 之后才第一次打开 App，那种情况弹「洗好了」只会造成困惑。
     */
    fun justFinished(previousStatus: Int?, currentStatus: Int): Boolean {
        if (previousStatus == null) return false
        val previous = com.shiro.classflow.data.model.wbu.WasherOrderPhase.fromStatus(previousStatus)
        val current = com.shiro.classflow.data.model.wbu.WasherOrderPhase.fromStatus(currentStatus)
        return previous.isActive && current.isTerminal
    }
}

private fun JsonObject.primitiveOrNull(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)

private fun JsonObject.stringOrNull(key: String): String? =
    primitiveOrNull(key)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/**
 * 字符串 / 数字都接受：实测 `status` 两种写法都存在。
 *
 * 小数写法（`"3299.0"`）也收：`remainTime` 一旦解析失败就会静默变成 0，
 * 页面上的倒计时会直接显示「即将完成」，这种错必须在这里挡掉。
 */
private fun JsonObject.intOrNull(key: String): Int? {
    val primitive = primitiveOrNull(key) ?: return null
    val raw = primitive.content.trim()
    raw.toIntOrNull()?.let { return it }
    return raw.toDoubleOrNull()?.toInt()
}

private fun JsonObject.longOrNull(key: String): Long? {
    val primitive = primitiveOrNull(key) ?: return null
    return primitive.content.trim().toLongOrNull()
}

private fun JsonObject.doubleOrNull(key: String): Double? {
    val primitive = primitiveOrNull(key) ?: return null
    return primitive.content.trim().toDoubleOrNull()
}
