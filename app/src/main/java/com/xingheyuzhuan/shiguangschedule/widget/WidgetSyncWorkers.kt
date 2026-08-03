package com.xingheyuzhuan.shiguangschedule.widget

import android.content.Context
import androidx.hilt.work.HiltWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xingheyuzhuan.shiguangschedule.data.sync.WidgetDataSynchronizer

/**
 * 负责每15分钟更新一次小组件UI的Worker。
 * 它直接调用所有小组件的UI更新，确保UI及时刷新。
 */
@HiltWorker
class WidgetUiUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Log.d("WidgetSync", "WidgetUiUpdateWorker 开始执行")
        // 调用通用的 UI 更新函数
        updateAllWidgets(applicationContext)
        return Result.success()
    }
}

/**
 * 负责每天执行一次完整数据同步的Worker。
 * 它调用 WidgetDataSynchronizer 的 syncNow() 方法，同步主数据库数据。
 */
@HiltWorker
class FullDataSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val widgetDataSynchronizer: WidgetDataSynchronizer
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Log.d("WidgetSync", "FullDataSyncWorker 开始执行")
        try {
            // 直接使用注入的实例
            widgetDataSynchronizer.syncNow()
            return Result.success()
        } catch (e: Exception) {
            // 如果同步失败，可以选择重试或失败
            return Result.failure()
        }
    }
}

