package com.xingheyuzhuan.shiguangschedule.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xingheyuzhuan.shiguangschedule.AppEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 洗衣闹钟的落点。
 *
 * 这里**直接**跑一次查询 + 发通知，不再转手交给 WorkManager。
 *
 * 为什么改：原来只调 [WasherReminderScheduler.enqueueOneShot]，把真正的活儿丢给
 * WorkManager。但实测在部分国产 ROM（vivo）上，WorkManager 的作业会一直停在
 * `RUNNABLE` 却不被调度 —— 闹钟准时响了，任务却永远不执行，用户收不到提醒。
 * 闹钟广播本身就是系统给我们的、已经拿到执行机会的时机，必须在这里把事做完。
 *
 * 广播的存活时间短（约 10 秒），但一次「换票 + 查详情」通常 1~3 秒就能完成；
 * 用 `goAsync()` 可以把这段时间拉到约 10 秒，够用。查完之后如果还没洗完，
 * [WasherWatcher.refresh] 自己会排下一次闹钟。
 */
class WasherAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        if (intent?.action != WasherReminderScheduler.ACTION_WASHER_ALARM) return
        Log.i(TAG, "洗衣闹钟触发")

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    ctx.applicationContext,
                    AppEntryPoint::class.java
                )
                // 关掉提醒后残留的闹钟：直接丢弃，不查也不响
                if (!entryPoint.washerOrderRepository().isReminderEnabled()) {
                    Log.i(TAG, "提醒已关闭，忽略这次闹钟")
                    return@launch
                }

                // 闹钟是按「这一单」排的，只查它的详情，省一次列表请求
                when (val result = entryPoint.washerWatcher().refresh(discover = false)) {
                    is WasherWatcher.Result.Failed -> {
                        Log.w(TAG, "闹钟检查失败，交给兜底轮询", result.cause)
                    }

                    is WasherWatcher.Result.Ok -> {
                        val phase = result.snapshot?.order?.phase
                        Log.i(TAG, "闹钟检查完成，订单状态=$phase")
                        // 还没洗完（闹钟早到了 / 服务端 remainTime 有抖动）：
                        // refresh 内部已经按新的剩余时间排了下一枪。
                        // 不再额外排一次，避免和它抢同一个 PendingIntent。
                        if (phase == null) {
                            WasherReminderScheduler.cancelAlarm(ctx)
                        }
                    }

                    WasherWatcher.Result.Idle -> {
                        Log.i(TAG, "闹钟检查完成：当前没有进行中的订单")
                        WasherReminderScheduler.cancelAlarm(ctx)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "处理洗衣闹钟失败", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "WasherAlarmReceiver"
    }
}
