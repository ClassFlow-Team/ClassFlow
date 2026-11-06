package com.xingheyuzhuan.shiguangschedule.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.shiro.classflow.data.model.wbu.WasherOrderPhase
import com.shiro.classflow.data.model.wbu.WasherOrderSnapshot
import com.shiro.classflow.data.model.wbu.WasherOrderSummary
import com.shiro.classflow.data.network.wbu.WasherOrderSession
import com.xingheyuzhuan.shiguangschedule.data.repository.WasherOrderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 洗衣进度的「看一眼」逻辑：查订单 → 落快照 → 该提醒就提醒 → 排下一次闹钟。
 *
 * 页面刷新、精确闹钟、15 分钟兜底轮询三条路都走这里，保证判定只有一份：
 * 什么时候算「洗完」、什么时候不该重复响、没有单时要不要停掉闹钟。
 */
@Singleton
class WasherWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WasherOrderRepository,
) {

    private companion object {
        const val TAG = "WasherWatcher"

        /** 闹钟比预计结束时间晚 45 秒：服务端 `remainTime` 是整秒取整，卡太紧会扑空。 */
        const val ALARM_GRACE_MILLIS = 45_000L

        /** 还在洗但没到点（比如闹钟被提前唤醒）时，下一次再看的时间。 */
        const val RETRY_DELAY_MILLIS = 3 * 60_000L
    }

    /** 一次刷新的结果，页面据此决定显示什么。 */
    sealed interface Result {
        /** 拿到了订单（可能是进行中，也可能刚结束）。 */
        data class Ok(val snapshot: WasherOrderSnapshot?) : Result

        /** 没有进行中的订单。 */
        data object Idle : Result

        /** 查询失败（未登录 / 网络 / 服务端异常）。 */
        data class Failed(val cause: Throwable) : Result
    }

    /**
     * 刷新一次。
     *
     * @param discover true 时先查 `orders/running` 找单（页面进入 / 后台轮询用）；
     *                 false 时只查已知那一单的详情（闹钟到点用，省一次请求）。
     * @param notify 是否允许发通知。用户手动下拉刷新时传 false，
     *               避免「刚洗完但用户就在页面上」还弹一条通知。
     */
    suspend fun refresh(discover: Boolean = true, notify: Boolean = true): Result {
        val previous = repository.currentSnapshot()?.order
        val order = try {
            if (discover) {
                discoverActiveOrder(previous)
            } else {
                previous?.let { snapshot ->
                    WasherOrderSession.withClient(context) { it.fetchWasherOrderDetail(snapshot.orderId) }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "洗衣订单查询失败", e)
            return Result.Failed(e)
        }

        if (order == null) {
            repository.saveSnapshot(null)
            // 只收本模块发过的通知，不能 cancelAll（会把上课提醒一起收掉）
            WasherNotification.cancel(context)
            WasherReminderScheduler.cancelAlarm(context)
            WasherReminderScheduler.cancelPeriodic(context)
            return Result.Idle
        }

        val fetchedAt = System.currentTimeMillis()
        val snapshot = WasherOrderSnapshot(order = order, fetchedAt = fetchedAt)
        repository.saveSnapshot(order, fetchedAt)

        // 提醒没开：不发通知、不排闹钟。进度照常落库（页面仍要显示倒计时），
        // 但绝不因为「刷新过」就替用户把提醒打开。
        val reminderOn = repository.isReminderEnabled()
        if (reminderOn && order.phase.isTerminal) {
            maybeNotify(previous, order, post = notify)
        }
        if (reminderOn) {
            reschedule(order, fetchedAt)
        } else {
            WasherReminderScheduler.cancelAlarm(context)
        }
        return Result.Ok(snapshot)
    }

    /**
     * 发通知的判定。
     *
     * 只有「之前是进行中、现在结束了」才算一次结束事件；第一次看到就已经结束的单不算
     * （用户可能是洗完才打开 App，弹「洗好了」只会莫名其妙）。
     *
     * @param post 用户就在页面上（手动刷新）时传 false：仍然**消费掉**这次事件
     *             （落去重标记），只是不弹通知 —— 否则后台轮询稍后还会再响一次。
     */
    private suspend fun maybeNotify(
        previous: WasherOrderSummary?,
        current: WasherOrderSummary,
        post: Boolean,
    ) {
        val previousPhase = previous?.phase
        val wasActive = previousPhase != null && previousPhase.isActive
        if (!wasActive) return
        if (previous?.orderId != current.orderId) return
        if (!repository.markNotifiedIfNew(current.orderId, current.status)) return
        if (post) WasherNotification.notify(context, current)
    }

    /** 排下一次闹钟；单已结束就取消。 */
    private suspend fun reschedule(order: WasherOrderSummary, fetchedAt: Long) {
        when {
            order.phase == WasherOrderPhase.RUNNING -> {
                val at = fetchedAt + order.remainSeconds * 1000L + ALARM_GRACE_MILLIS
                WasherReminderScheduler.scheduleAlarm(context, at)
            }

            order.phase.isTerminal -> WasherReminderScheduler.cancelAlarm(context)

            // 待支付 / 待启动：服务端不会自己动，等用户回来按按钮；
            // 但保持一个较慢的兜底检查，避免用户忘了还占着机器。
            else -> WasherReminderScheduler.scheduleAlarm(
                context,
                System.currentTimeMillis() + RETRY_DELAY_MILLIS * 5
            )
        }
    }

    /**
     * 找「当前该盯的那一单」。
     *
     * 优先运行中（用户最关心），其次已支付待启动，最后待支付 —— 同时有多单时
     * 按这个优先级取，避免把「另一台待付款的单」当成正在洗的那台。
     *
     * 服务端 `orders/running` 一旦把单移出（洗完了），列表就是空的；这时**回头查一次
     * 上一单的详情**，把终态拿回来 —— 否则页面会直接从「洗涤中」跳到「没有订单」，
     * 用户看不到「已洗完」，后台也失去了发完成提醒的时机。
     */
    private suspend fun discoverActiveOrder(previous: WasherOrderSummary?): WasherOrderSummary? {
        val orders = WasherOrderSession.withClient(context) { it.fetchRunningWasherOrders() }
        val candidates = orders.filter { it.phase.isActive }
        if (candidates.isNotEmpty()) return candidates.minByOrNull { priority(it.phase) }

        if (previous != null && previous.phase.isActive) {
            val finished = WasherOrderSession.withClient(context) {
                it.fetchWasherOrderDetail(previous.orderId)
            }
            if (finished != null) return finished
        }
        return null
    }

    private fun priority(phase: WasherOrderPhase): Int = when (phase) {
        WasherOrderPhase.RUNNING -> 0
        WasherOrderPhase.PAID -> 1
        WasherOrderPhase.RESERVED -> 2
        else -> 3
    }

    /**
     * 用户关掉提醒 / 退出登录时清场：取消闹钟、取消轮询、收掉通知。
     *
     * **不清快照**：关提醒不等于「我不想看进度」，页面仍然要能显示那张卡片。
     */
    suspend fun stopWatching(clearSnapshot: Boolean = false) {
        WasherReminderScheduler.cancelAlarm(context)
        WasherReminderScheduler.cancelPeriodic(context)
        WasherNotification.cancel(context)
        if (clearSnapshot) {
            repository.clearSnapshot()
            WasherOrderSession.clear()
        }
    }

    /**
     * 按当前设置决定「后台要不要继续查」。
     *
     * 只有「提醒开着 + 后台查询开着 + 确实有进行中的单」才挂 15 分钟轮询；
     * 其余情况一律取消，避免用户关了开关还在后台打接口。
     */
    suspend fun syncBackgroundSchedule() {
        val reminderOn = repository.isReminderEnabled()
        val pollingOn = repository.isBackgroundPollingEnabled()
        val hasActive = repository.currentSnapshot()?.order?.phase?.isActive == true
        if (reminderOn && pollingOn && hasActive) {
            WasherReminderScheduler.ensurePeriodic(context)
        } else {
            WasherReminderScheduler.cancelPeriodic(context)
        }
        if (!reminderOn) {
            WasherReminderScheduler.cancelAlarm(context)
        }
    }

    /** 页面用的便捷方法：当前快照（可能为空）。 */
    suspend fun currentSnapshot(): WasherOrderSnapshot? = repository.snapshot.first()

    /**
     * 按当前快照重排一次闹钟（页面离开 / 切后台时用）。
     *
     * 为什么不直接调 `WasherReminderScheduler.scheduleAlarm`：闹钟的提前量、
     * 「提醒没开就不排」这两条规则只该有一份，散在页面里迟早会漂。
     */
    suspend fun scheduleFromCurrentSnapshot() {
        if (!repository.isReminderEnabled()) return
        val snapshot = repository.currentSnapshot() ?: return
        val order = snapshot.order
        if (order.phase != WasherOrderPhase.RUNNING) return
        WasherReminderScheduler.scheduleAlarm(
            context,
            snapshot.fetchedAt + order.remainSeconds * 1000L + ALARM_GRACE_MILLIS
        )
        if (repository.isBackgroundPollingEnabled()) {
            WasherReminderScheduler.ensurePeriodic(context)
        }
    }
}

/**
 * 洗衣提醒的调度器：精确闹钟 + 15 分钟兜底轮询。
 *
 * 为什么两者都要：
 * - 精确闹钟负责「准时」，但 Doze / 厂商省电会让它延迟甚至不触发；
 * - 周期 Worker 负责「最终一定提醒到」，代价是可能晚十几分钟。
 * 两者都跑同一条 [WasherWatcher.refresh]，谁先看到结束谁发通知，去重由仓库保证。
 */
object WasherReminderScheduler {

    private const val TAG = "WasherReminderSched"

    private const val ALARM_REQUEST_CODE = 7_431

    private const val PERIODIC_WORK_NAME = "WasherWatchWorker_Periodic"
    private const val ONE_SHOT_WORK_NAME = "WasherCheckWorker_OneShot"

    /** 闹钟触发的广播（见 `WasherAlarmReceiver`）。 */
    const val ACTION_WASHER_ALARM = "com.xingheyuzhuan.shiguangschedule.action.WASHER_ALARM"

    fun scheduleAlarm(context: Context, triggerAtMillis: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val now = System.currentTimeMillis()
        // 已经过点的立刻补一次（用 one-shot worker 而不是闹钟：闹钟设过去的时间不会触发）
        if (triggerAtMillis <= now + 1_000L) {
            enqueueOneShot(context)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
            // 没有精确闹钟权限：退化成「轮询尽快跑一次」，不静默失败
            Log.i(TAG, "无精确闹钟权限，改用一次性 Worker")
            enqueueOneShot(context)
            return
        }
        val pending = alarmPendingIntent(context) ?: run {
            enqueueOneShot(context)
            return
        }
        runCatching {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }.onFailure {
            Log.w(TAG, "设置洗衣闹钟失败，改用一次性 Worker", it)
            enqueueOneShot(context)
        }
    }

    fun cancelAlarm(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = alarmPendingIntent(context, create = false) ?: return
        manager.cancel(pending)
        pending.cancel()
    }

    private fun alarmPendingIntent(context: Context, create: Boolean = true): PendingIntent? {
        val intent = Intent(context, WasherAlarmReceiver::class.java).apply {
            action = ACTION_WASHER_ALARM
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (create) {
            PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
        } else {
            PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    /** 15 分钟兜底轮询（唯一任务名，重复调用只更新不叠加）。 */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<WasherWatchWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    /** 立刻跑一次（闹钟到点 / 没有精确闹钟权限时的退化路径）。 */
    fun enqueueOneShot(context: Context) {
        val request = OneTimeWorkRequestBuilder<WasherCheckWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
