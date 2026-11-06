package com.shiro.classflow.data.network.wbu

import com.shiro.classflow.data.model.wbu.WasherOrderPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 洗衣机订单解析与状态映射单测。
 *
 * 夹具直接取自实测 HAR（2026-10-16 南B-12「普通洗 + 60度高温洗」一单），
 * 覆盖三个关键点：
 * 1. `status` 在详情接口里是**字符串**、在历史列表里是**数字**；
 * 2. 异常终态（`60+`）绝不能算成「洗完」；
 * 3. 第一次看到就已经结束的单不该触发提醒。
 */
class WasherOrderParserTest {

    /** `orders/1658631386/detail?orderId=` 的真实响应（节选，字段顺序保留）。 */
    private val detailBody = """
        {"code":0, "data":{"countDown":0, "createAt":"2026-10-16T14:27:04Z",
        "cycle":"普通洗 | 60度高温洗", "deviceId":"09b68777656aafcc507f26391aefb17a",
        "deviceNo":"000001", "deviceTypeId":9, "deviceTypeName":"10kg滚筒机",
        "id":1658631386, "orderId":1658631386, "orderNo":"202610162227041429941",
        "payPrice":4.9, "payment":4.9, "remainTime":3299, "status":"40",
        "statusRemark":"运行中", "storeName":"南B-12", "workTime":48}, "message":""}
    """.trimIndent()

    /** 刚下单、还没支付时的详情。 */
    private val reservedBody = """
        {"code":0, "data":{"countDown":179, "deviceId":"09b68777656aafcc507f26391aefb17a",
        "deviceNo":"000001", "id":1658631386, "orderId":1658631386, "payPrice":4.9,
        "status":"10", "statusRemark":"已预约", "storeName":"南B-12", "workTime":48}}
    """.trimIndent()

    /** 历史列表：`status` 是数字，且金额字段可能缺失。 */
    private val historyBody = """
        {"code":0, "data":[
          {"orderId":1658631386, "deviceNo":"000001", "status":50, "storeName":"南B-12",
           "cycle":"普通洗 | 60度高温洗", "payPrice":4.9, "createAt":"2026-10-16T14:27:04Z"},
          {"orderId":1658631387, "deviceNo":"000002", "status":60, "storeName":"南B-11",
           "payPrice":3.4, "createAt":"2026-10-15T10:00:00Z"}
        ], "message":""}
    """.trimIndent()

    @Test
    fun `detail parses string status into running phase`() {
        val order = WasherOrderParser.parseOrderDetail(detailBody)!!
        assertEquals(1658631386L, order.orderId)
        assertEquals(40, order.status)
        assertEquals(WasherOrderPhase.RUNNING, order.phase)
        assertEquals("南B-12", order.storeName)
        assertEquals("000001", order.deviceNo)
        assertEquals(48, order.workTimeMinutes)
        assertEquals(3299, order.remainSeconds)
        assertEquals(4.9, order.payPrice, 0.001)
        assertEquals("南B-12-01", order.machineTitle)
    }

    @Test
    fun `reserved order is not treated as running`() {
        val order = WasherOrderParser.parseOrderDetail(reservedBody)!!
        assertEquals(WasherOrderPhase.RESERVED, order.phase)
        assertTrue(order.phase.isActive)
        assertFalse(order.phase.isTerminal)
        // 待支付没有剩余时间，倒计时不该凭空开始走
        assertEquals(0, order.remainingSeconds(System.currentTimeMillis(), System.currentTimeMillis()))
    }

    @Test
    fun `history parses numeric status and keeps abnormal orders apart`() {
        val orders = WasherOrderParser.parseOrderList(historyBody)
        assertEquals(2, orders.size)
        assertEquals(WasherOrderPhase.DONE, orders[0].phase)
        assertEquals(WasherOrderPhase.ERROR, orders[1].phase)
        assertTrue(orders[0].phase.isTerminal)
        assertTrue(orders[1].phase.isTerminal)
        // 异常单绝不能是「洗完了」
        assertFalse(orders[1].isDone)
        assertTrue(orders[1].isFailed)
    }

    @Test
    fun `unknown status code is never guessed as done`() {
        // 41~49 / 100+ 是没见过的码：既不当作洗完，也不当作异常
        assertEquals(WasherOrderPhase.UNKNOWN, WasherOrderPhase.fromStatus(45))
        assertEquals(WasherOrderPhase.UNKNOWN, WasherOrderPhase.fromStatus(200))
        assertEquals(WasherOrderPhase.UNKNOWN, WasherOrderPhase.fromStatus(-1))
        assertFalse(WasherOrderPhase.UNKNOWN.isTerminal)
        assertFalse(WasherOrderPhase.UNKNOWN.isActive)
    }

    @Test
    fun `remaining time counts down from fetch moment`() {
        val order = WasherOrderParser.parseOrderDetail(detailBody)!!
        val fetchedAt = 1_000_000L
        assertEquals(3299, order.remainingSeconds(fetchedAt, fetchedAt))
        assertEquals(3239, order.remainingSeconds(fetchedAt + 60_000L, fetchedAt))
        // 过了很久也不会变负数
        assertEquals(0, order.remainingSeconds(fetchedAt + 10_000_000L, fetchedAt))
    }

    @Test
    fun `progress is clamped between zero and one`() {
        val order = WasherOrderParser.parseOrderDetail(detailBody)!!
        val fetchedAt = 0L
        val start = order.progress(fetchedAt, fetchedAt)
        assertTrue(start in 0f..1f)
        // 剩余时间归零 = 进度拉满（不会超过 1）
        assertEquals(1f, order.progress(fetchedAt + 10_000_000L, fetchedAt), 0.0001f)
    }

    @Test
    fun `first sighting of a finished order does not notify`() {
        // 用户洗完才第一次打开 App：不提醒
        assertFalse(WasherOrderParser.justFinished(previousStatus = null, currentStatus = 50))
        // 从运行中变成完成：提醒
        assertTrue(WasherOrderParser.justFinished(previousStatus = 40, currentStatus = 50))
        // 运行中变成异常：也提醒（否则用户永远不知道失败了）
        assertTrue(WasherOrderParser.justFinished(previousStatus = 40, currentStatus = 60))
        // 已经是终态再刷新：不重复提醒
        assertFalse(WasherOrderParser.justFinished(previousStatus = 50, currentStatus = 50))
    }

    @Test
    fun `malformed body yields null instead of throwing`() {
        assertNull(WasherOrderParser.parseOrderDetail("not json"))
        assertNull(WasherOrderParser.parseOrderDetail("""{"code":0}"""))
        assertTrue(WasherOrderParser.parseOrderList("<html>").isEmpty())
    }

    @Test
    fun `missing device number keeps title on store name only`() {
        val order = WasherOrderParser.parseOrderDetail(
            """{"code":0,"data":{"orderId":7,"status":50,"storeName":"南B-12"}}"""
        )!!
        assertNull(order.shortDeviceNo)
        assertEquals("南B-12", order.machineTitle)
    }
}
