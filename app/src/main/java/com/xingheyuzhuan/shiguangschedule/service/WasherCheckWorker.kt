package com.xingheyuzhuan.shiguangschedule.service

import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.shiro.classflow.data.model.wbu.WasherOrderPhase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 精确闹钟到点后的「看一眼」。
 *
 * 只查已知那一单的详情（`orders/{id}/detail`），不重新列进行中订单：
 * 闹钟是按 `remainTime` 精确排的，到点那一刻最该看的就是这一单。
 */
@HiltWorker
class WasherCheckWorker @AssistedInject constructor(
    @Assisted appContext: android.content.Context,
    @Assisted params: WorkerParameters,
    private val watcher: WasherWatcher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "洗衣检查开始（来源：闹钟 / 延迟任务）")
        return runCatching {
            when (val result = watcher.refresh(discover = false)) {
                is WasherWatcher.Result.Failed -> {
                    // 网络抖动很常见，交给 WorkManager 退避重试；永久失败不重试
                    if (runAttemptCount < 3) Result.retry() else Result.success()
                }

                else -> {
                    val phase = (result as? WasherWatcher.Result.Ok)?.snapshot?.order?.phase
                    // 还在洗说明闹钟早到了（服务端 remainTime 有抖动），补一次延时检查
                    if (phase == WasherOrderPhase.RUNNING) {
                        WasherReminderScheduler.scheduleAlarm(
                            applicationContext,
                            System.currentTimeMillis() + RETRY_WHILE_RUNNING_MILLIS
                        )
                    }
                    Result.success()
                }
            }
        }.getOrElse {
            Log.w(TAG, "洗衣检查失败", it)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val TAG = "WasherCheckWorker"

        /** 到点还在洗：说明这次是提前唤醒，3 分钟后再看。 */
        const val RETRY_WHILE_RUNNING_MILLIS = 3 * 60_000L
    }
}
