package com.xingheyuzhuan.shiguangschedule.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xingheyuzhuan.shiguangschedule.AppEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 洗衣闹钟的落点。
 *
 * 只做一件事：把「该看订单了」转成一次 WorkManager 任务。
 * 不在广播里直接发网络请求 —— 广播的存活时间很短，`goAsync()` 也撑不住一次换票 + 查询。
 */
class WasherAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context ?: return
        if (intent?.action != WasherReminderScheduler.ACTION_WASHER_ALARM) return
        Log.i(TAG, "洗衣闹钟触发")

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
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
                WasherReminderScheduler.enqueueOneShot(ctx)
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
