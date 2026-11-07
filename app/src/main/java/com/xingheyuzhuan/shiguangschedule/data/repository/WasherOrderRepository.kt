package com.xingheyuzhuan.shiguangschedule.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shiro.classflow.data.model.wbu.WasherOrderSnapshot
import com.shiro.classflow.data.model.wbu.WasherOrderSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 洗衣进度与提醒的本地存储。
 *
 * 三块内容各存各的 key：
 * - [snapshot]：最近一次拿到的进行中订单（含拉取时刻，倒计时能接着走）；
 * - [lastNotifiedStatus]：上次已提醒的状态码，保证同一单只响一次；
 * - 两个开关：完成提醒（默认关）、后台自动查询（默认开）。
 *
 * 与「校园设备记录」分文件存放：那份是使用历史，这份是临时的订单状态，
 * 清缓存 / 退出登录时应该能单独丢掉，不该牵连设备列表。
 */
@Singleton
class WasherOrderRepository @Inject constructor(
    @Named("WasherOrder") private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val SNAPSHOT = stringPreferencesKey("active_order_snapshot")
        val LAST_NOTIFIED = stringPreferencesKey("last_notified_status")
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        val BACKGROUND_POLLING = booleanPreferencesKey("background_polling")
        val LAST_CHECK_AT = longPreferencesKey("last_check_at")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val prefs: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    // ───────────────────────── 订单快照 ─────────────────────────

    /** 最近一次拿到的进行中订单（没有就是 null）。 */
    val snapshot: Flow<WasherOrderSnapshot?> = prefs.map { preferences ->
        decodeSnapshot(preferences[Keys.SNAPSHOT])
    }

    suspend fun currentSnapshot(): WasherOrderSnapshot? = snapshot.first()

    /**
     * 落一份新快照。
     *
     * 没有进行中订单时传 null 清空 —— 页面据此显示空状态，
     * 后台也据此知道「没单可盯，别再挂闹钟」。
     */
    suspend fun saveSnapshot(order: WasherOrderSummary?, fetchedAt: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            if (order == null) {
                preferences.remove(Keys.SNAPSHOT)
            } else {
                preferences[Keys.SNAPSHOT] = json.encodeToString(
                    WasherOrderSnapshot.serializer(),
                    WasherOrderSnapshot(order = order, fetchedAt = fetchedAt)
                )
            }
            preferences[Keys.LAST_CHECK_AT] = fetchedAt
        }
    }

    suspend fun clearSnapshot() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.SNAPSHOT)
            preferences.remove(Keys.LAST_NOTIFIED)
        }
    }

    // ───────────────────────── 提醒去重 ─────────────────────────

    /** 已经提醒过的「订单 + 状态」标记（`orderId:status`）。 */
    suspend fun lastNotified(): String? = prefs.map { it[Keys.LAST_NOTIFIED] }.first()

    /**
     * 判断这一单的这个状态是否需要提醒，并在需要时**先落标记再返回 true**。
     *
     * 先落标记再提醒是有意的：通知发出后进程随时可能被杀，
     * 若反过来（先发后记）就会在异常路径上重复响铃。
     */
    suspend fun markNotifiedIfNew(orderId: Long, status: Int): Boolean {
        val marker = "$orderId:$status"
        var shouldNotify = false
        dataStore.edit { preferences ->
            if (preferences[Keys.LAST_NOTIFIED] != marker) {
                preferences[Keys.LAST_NOTIFIED] = marker
                shouldNotify = true
            }
        }
        return shouldNotify
    }

    // ───────────────────────── 开关 ─────────────────────────

    /** 洗衣完成提醒：**默认关闭**（首次使用由用户在设置里手动打开）。 */
    val reminderEnabled: Flow<Boolean> = prefs.map { it[Keys.REMINDER_ENABLED] ?: false }

    suspend fun setReminderEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.REMINDER_ENABLED] = enabled }
    }

    /** 后台自动查询：默认开启（只关掉轮询，打开 App 时仍会查）。 */
    val backgroundPollingEnabled: Flow<Boolean> = prefs.map { it[Keys.BACKGROUND_POLLING] ?: true }

    suspend fun setBackgroundPollingEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BACKGROUND_POLLING] = enabled }
    }

    suspend fun isReminderEnabled(): Boolean = reminderEnabled.first()

    suspend fun isBackgroundPollingEnabled(): Boolean = backgroundPollingEnabled.first()

    /** 上次成功查询的时间（毫秒），页面用来判断「数据是不是太旧了」。 */
    val lastCheckAt: Flow<Long> = prefs.map { it[Keys.LAST_CHECK_AT] ?: 0L }

    private fun decodeSnapshot(raw: String?): WasherOrderSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString(WasherOrderSnapshot.serializer(), raw)
        }.getOrNull()
    }
}
