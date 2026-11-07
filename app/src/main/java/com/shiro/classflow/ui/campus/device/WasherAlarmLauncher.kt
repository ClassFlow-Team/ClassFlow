package com.shiro.classflow.ui.campus.device

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * 把洗衣结束时间交给系统时钟 App。
 *
 * 为什么要有这个：实测本应用的后台提醒在部分机型（vivo 等）上会被省电策略拦掉 ——
 * 闹钟唤醒了，但代码跑不完，通知发不出来。与其给用户一个「开了但可能不响」的开关，
 * 不如直接把他送到系统时钟：那是唯一真正可靠的通道。
 *
 * 两种方式，各有取舍：
 * - **闹钟**（[WasherAlarmKind.ALARM]）：到点响铃，支持「仅有这一次」。缺点是会留在
 *   闹钟列表里，用得多了会越积越多；「响后自动删除」是厂商时钟 App 的私有开关，
 *   App 无法替用户勾选（`AlarmClock` 没有对应参数，ClockProvider 也不对外导出）。
 * - **倒计时器**（[WasherAlarmKind.TIMER]）：天生一次性，响完自动消失，不污染闹钟列表，
 *   语义也更贴合「还剩多少分钟洗完」。
 *
 * 两条路径都**不带** `EXTRA_SKIP_UI`：确认交给用户，避免「App 偷偷给我设了个闹钟」。
 *
 * 注意：这两个入口都由系统时钟的 `HandleApiCalls` 处理，受
 * `com.android.alarm.permission.SET_ALARM` 保护（见 manifest）。
 */
enum class WasherAlarmKind {
    /** 系统闹钟：到点响铃，会留在闹钟列表。 */
    ALARM,

    /** 系统倒计时器：响完自动消失，不占闹钟列表。 */
    TIMER,
}

/** 倒计时上限：24 小时。超过这个量级不是洗衣，且 Int 溢出会让时钟 App 静默忽略。 */
private const val MAX_TIMER_SECONDS = 24L * 60 * 60

/**
 * 打开系统时钟，预填好洗衣结束的时刻 / 剩余时长，让用户确认。
 *
 * @param triggerAtMillis 预计洗完的时刻（用于闹钟方式）。
 * @param remainingSeconds 剩余秒数（用于倒计时方式）；不足 1 秒按 1 秒算。
 * @return true 表示已成功拉起系统时钟；false 表示这台机器没有可用的时钟 App。
 */
fun openSystemAlarmForWasher(
    context: Context,
    kind: WasherAlarmKind,
    triggerAtMillis: Long,
    remainingSeconds: Long,
): Boolean {
    val label = context.getString(com.xingheyuzhuan.shiguangschedule.R.string.washer_alarm_label)
    val intent = when (kind) {
        WasherAlarmKind.ALARM -> {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
            Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, cal.get(java.util.Calendar.HOUR_OF_DAY))
                putExtra(AlarmClock.EXTRA_MINUTES, cal.get(java.util.Calendar.MINUTE))
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
            }
        }

        WasherAlarmKind.TIMER -> Intent(AlarmClock.ACTION_SET_TIMER).apply {
            // EXTRA_LENGTH 的类型必须是 **Int**（单位：秒）。
            // 传 Long 时时钟 App 用 getIntExtra 读不到，会静默采用它自己的默认值
            // （实测 vivo 显示 30:00），表现为「倒计时时间不对」。
            //
            // 上限按 24 小时截断：洗衣计时不可能超过这个量级，而 Int 溢出会得到
            // 负数时长，时钟 App 直接忽略 —— 又是一个静默失败。
            val seconds = remainingSeconds
                .coerceIn(1L, MAX_TIMER_SECONDS)
                .toInt()
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    return runCatching { context.startActivity(intent) }.isSuccess
}
