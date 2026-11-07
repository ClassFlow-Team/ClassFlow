package com.xingheyuzhuan.shiguangschedule.service

import com.shiro.classflow.data.model.wbu.WasherOrderPhase
import com.shiro.classflow.data.model.wbu.WasherOrderSummary

/**
 * 洗衣完成提醒「该怎么排」的纯决策。
 *
 * 单独抽出来是为了能写纯 JVM 单测：这里出错的代价很高 —— 曾经在没有精确闹钟权限时
 * 退化成「立刻重跑一次」，Worker 发现还在洗又调回来排闹钟，于是 1 秒内连跑数轮，
 * 后台接口被打爆。这种自激循环必须被测试挡住。
 */
internal enum class WasherAlarmStrategy {
    /** 目标时间已过（或马上就到）：立刻补跑一次检查。 */
    IMMEDIATE,

    /** 权限齐全：交给系统精确闹钟，到点唤醒。 */
    EXACT_ALARM,

    /**
     * 排不了精确闹钟（权限被拒 / 系统抛异常）：改用**带初始延迟**的一次性任务。
     *
     * 关键是不能退化成 [IMMEDIATE]：那样任务会立刻跑完、发现还没洗好、再排一次，
     * 形成不间断的忙等。
     */
    DELAYED_WORK,
}

/**
 * 决定本次该用哪种方式排检查。
 *
 * @param triggerAtMillis 期望触发的时间戳（毫秒）。
 * @param nowMillis 当前时间戳（毫秒），由调用方传入以便测试。
 * @param canScheduleExactAlarms 系统是否允许本应用排精确闹钟（Android 12+ 默认不允许）。
 */
internal fun decideWasherAlarmStrategy(
    triggerAtMillis: Long,
    nowMillis: Long,
    canScheduleExactAlarms: Boolean,
): WasherAlarmStrategy = when {
    // 闹钟设过去的时间不会触发，只能立刻补跑一次
    triggerAtMillis <= nowMillis + IMMEDIATE_THRESHOLD_MILLIS -> WasherAlarmStrategy.IMMEDIATE
    !canScheduleExactAlarms -> WasherAlarmStrategy.DELAYED_WORK
    else -> WasherAlarmStrategy.EXACT_ALARM
}

/** 与目标时间相差不到 1 秒就算「已过点」，直接补跑。 */
internal const val IMMEDIATE_THRESHOLD_MILLIS = 1_000L

/**
 * 下一次该在什么时刻检查这一单。
 *
 * - 运行中：按「拉取时刻 + 真实剩余秒数 + [graceMillis]」算，**不**用界面那 +60 秒的
 *   显示补偿，否则会提前一分钟喊「洗好了」。
 * - 待支付 / 待启动：服务端不会自己推进，用一个固定的慢速复查。
 *
 * @param graceMillis 闹钟相对真实结束时间的延后量（由调用方决定，通常 45 秒）。
 */
internal fun nextCheckAtMillis(
    order: WasherOrderSummary,
    fetchedAtMillis: Long,
    retryDelayMillis: Long,
    nowMillis: Long,
    graceMillis: Long,
): Long = when {
    order.phase == WasherOrderPhase.RUNNING ->
        fetchedAtMillis + order.remainSeconds * 1000L + graceMillis

    order.phase.isTerminal -> nowMillis

    else -> nowMillis + retryDelayMillis
}
