package com.xingheyuzhuan.shiguangschedule.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.shiro.classflow.MainActivity
import com.shiro.classflow.data.model.wbu.WasherOrderPhase
import com.shiro.classflow.data.model.wbu.WasherOrderSummary
import com.xingheyuzhuan.shiguangschedule.R

/**
 * 「衣服洗好了」/「洗衣异常」通知。
 *
 * 与课程提醒共用同一个 Activity 落地页，但用**独立通知渠道**：
 * 洗衣是几十秒到一小时的短事件，用户很可能想单独静音它而不影响上课提醒。
 */
object WasherNotification {

    const val CHANNEL_ID = "washer_notification_channel"

    private const val TAG = "WasherNotification"

    /** 通知 ID 基址：`orderId` 取模后加上去，保证同一单重复提醒也只占一个坑。 */
    private const val NOTIFICATION_ID_BASE = 7_400

    /** 建渠道（幂等）。渠道一旦建好，名字 / 重要级就由系统接管，改文案不会影响已建渠道。 */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.washer_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.washer_notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 发一条洗衣状态通知。
     *
     * @param order 当前订单；[WasherOrderPhase.DONE] 走「洗好了」，[WasherOrderPhase.ERROR] 走「异常」，
     *              其它状态直接不发（调用方不该在运行中打扰用户）。
     */
    fun notify(context: Context, order: WasherOrderSummary) {
        val phase = order.phase
        if (phase != WasherOrderPhase.DONE && phase != WasherOrderPhase.ERROR) return
        ensureChannel(context)

        val manager = context.getSystemService<NotificationManager>() ?: return
        val done = phase == WasherOrderPhase.DONE

        val title = context.getString(
            if (done) R.string.washer_notification_done_title else R.string.washer_notification_error_title
        )
        val machine = order.machineTitle
            ?: context.getString(R.string.washer_notification_unknown_machine)
        val body = if (done) {
            context.getString(R.string.washer_notification_done_body, machine)
        } else {
            context.getString(
                R.string.washer_notification_error_body,
                machine,
                order.statusRemark?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.washer_status_error)
            )
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_WASHER_PROGRESS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(
                BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
            )
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val id = NOTIFICATION_ID_BASE + (order.orderId % 1_000).toInt()
        postedIds += id
        runCatching { manager.notify(id, notification) }
            .onFailure { Log.w(TAG, "洗衣通知发送失败", it) }
    }

    /**
     * 用户关掉提醒 / 订单已清空时，把本模块发过的通知收掉。
     *
     * **不能 `cancelAll()`**：那会顺手把上课提醒、更新提醒一起收走。
     */
    fun cancel(context: Context, orderId: Long? = null) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (orderId != null) {
            val id = NOTIFICATION_ID_BASE + (orderId % 1_000).toInt()
            postedIds -= id
            manager.cancel(id)
            return
        }
        postedIds.forEach { manager.cancel(it) }
        postedIds.clear()
    }

    /** 本进程发过的通知 ID（进程被杀后残留的通知由系统在点击 / 自动取消时清掉）。 */
    private val postedIds = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
}
