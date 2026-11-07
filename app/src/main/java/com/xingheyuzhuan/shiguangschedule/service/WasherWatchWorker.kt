package com.xingheyuzhuan.shiguangschedule.service

import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 15 分钟兜底轮询。
 *
 * 存在的唯一理由：精确闹钟在 Doze / 厂商省电下会被延迟甚至吞掉。
 * 这个 Worker 不追求准时，只保证「最终一定能发现订单结束」。
 *
 * 它同时承担「没单了就自己停」的职责：查到没有进行中订单时，
 * 顺手把周期任务取消掉，不在后台空转。
 */
@HiltWorker
class WasherWatchWorker @AssistedInject constructor(
    @Assisted appContext: android.content.Context,
    @Assisted params: WorkerParameters,
    private val watcher: WasherWatcher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "洗衣兜底轮询")
        return runCatching {
            val result = watcher.refresh(discover = true)
            if (result is WasherWatcher.Result.Failed) {
                // 登录失效这类问题重试也没用，交给用户进页面时重新登录
                return@runCatching Result.success()
            }
            // 没有进行中的单 / 已经结束：周期任务没有意义了
            watcher.syncBackgroundSchedule()
            Result.success()
        }.getOrElse {
            Log.w(TAG, "洗衣兜底轮询失败", it)
            Result.success()
        }
    }

    private companion object {
        const val TAG = "WasherWatchWorker"
    }
}
