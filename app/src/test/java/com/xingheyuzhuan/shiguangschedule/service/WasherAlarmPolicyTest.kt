package com.xingheyuzhuan.shiguangschedule.service

import com.shiro.classflow.data.model.wbu.WasherOrderSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 洗衣提醒的调度决策测试。
 *
 * 这里的每一条都对应一次真机事故，不是「为了覆盖率而写」：
 * - 没有精确闹钟权限时必须走**延迟**任务，否则会自激循环（1 秒内连跑数轮）；
 * - 到点计算必须用真实剩余秒数，不能用界面那 +60 秒的显示补偿。
 */
class WasherAlarmPolicyTest {

    private val now = 1_000_000L

    // ───────────── 策略选择 ─────────────

    @Test
    fun `exact alarm is used when permission is granted`() {
        assertEquals(
            WasherAlarmStrategy.EXACT_ALARM,
            decideWasherAlarmStrategy(
                triggerAtMillis = now + 30 * 60_000L,
                nowMillis = now,
                canScheduleExactAlarms = true,
            )
        )
    }

    /**
     * 关键回归：没有精确闹钟权限时**绝不能**选 IMMEDIATE。
     *
     * 这是之前真机上的自激循环：Worker 立刻跑完 → 发现还在洗 → 再排 → 又立刻跑……
     * 实测 1 秒内连跑 5 轮，每轮都打一次订单详情接口。
     */
    @Test
    fun `missing exact alarm permission decays to delayed work not immediate`() {
        val strategy = decideWasherAlarmStrategy(
            triggerAtMillis = now + 30 * 60_000L,
            nowMillis = now,
            canScheduleExactAlarms = false,
        )
        assertEquals(WasherAlarmStrategy.DELAYED_WORK, strategy)
        assertNotEquals(WasherAlarmStrategy.IMMEDIATE, strategy)
    }

    @Test
    fun `past trigger time falls back to immediate check`() {
        // 目标时间已过去：闹钟设过去的时间不会触发，只能立刻补跑
        assertEquals(
            WasherAlarmStrategy.IMMEDIATE,
            decideWasherAlarmStrategy(
                triggerAtMillis = now - 5_000L,
                nowMillis = now,
                canScheduleExactAlarms = true,
            )
        )
    }

    @Test
    fun `trigger time within one second counts as past`() {
        assertEquals(
            WasherAlarmStrategy.IMMEDIATE,
            decideWasherAlarmStrategy(
                triggerAtMillis = now + 500L,
                nowMillis = now,
                canScheduleExactAlarms = false,
            )
        )
    }

    // ───────────── 到点计算 ─────────────

    @Test
    fun `running order is checked after its real remain time plus grace`() {
        val order = WasherOrderSummary(
            orderId = 1L,
            status = 40,
            remainSeconds = 2_445,
        )
        val at = nextCheckAtMillis(
            order = order,
            fetchedAtMillis = now,
            retryDelayMillis = 60_000L,
            nowMillis = now,
            graceMillis = 45_000L,
        )
        assertEquals(now + 2_445 * 1000L + 45_000L, at)
    }

    /**
     * 界面为了对齐官方 H5 会多显示 60 秒，但闹钟绝不能跟着延后。
     *
     * 多加 1 分钟意味着「洗好了」比实际晚一分钟才响，用户站在机器前等不到提醒。
     */
    @Test
    fun `alarm does not include the sixty second display offset`() {
        val order = WasherOrderSummary(orderId = 1L, status = 40, remainSeconds = 600)
        val at = nextCheckAtMillis(
            order = order,
            fetchedAtMillis = now,
            retryDelayMillis = 60_000L,
            nowMillis = now,
            graceMillis = 0L,
        )
        assertEquals(now + 600 * 1000L, at)
        // 明确排除「+60 秒显示补偿」这条错误口径
        assertNotEquals(now + (600 + 60) * 1000L, at)
    }

    @Test
    fun `paid but not started order gets a slow retry`() {
        val order = WasherOrderSummary(orderId = 1L, status = 20)
        val at = nextCheckAtMillis(
            order = order,
            fetchedAtMillis = now,
            retryDelayMillis = 15 * 60_000L,
            nowMillis = now,
            graceMillis = 45_000L,
        )
        assertEquals(now + 15 * 60_000L, at)
    }

    @Test
    fun `terminal order is due immediately`() {
        val order = WasherOrderSummary(orderId = 1L, status = 50)
        val at = nextCheckAtMillis(
            order = order,
            fetchedAtMillis = now,
            retryDelayMillis = 15 * 60_000L,
            nowMillis = now + 123L,
            graceMillis = 45_000L,
        )
        assertEquals(now + 123L, at)
    }
}
