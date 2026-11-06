package com.xingheyuzhuan.shiguangschedule.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shiro.classflow.data.model.wbu.CampusDeviceEntry
import com.shiro.classflow.data.model.wbu.CampusDeviceKind
import com.shiro.classflow.data.network.wbu.CampusShowerLink
import com.shiro.classflow.data.network.wbu.ShowerManualCode
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 「校园设备管理」的本地存储：饮水机 / 洗衣机 / 洗浴的使用记录、重命名与桌面图标。
 *
 * 只存本机（独立 DataStore 文件 `campus_device_hub`），刻意不参与 WebDAV 同步：
 * 「我扫过哪几台设备、给它们起了什么名字、桌面上放了哪个图标」是这台手机的私事，
 * 与吹风机记录（`hairdryer_hub`）同一待遇。
 *
 * 三类设备共用一个存储文件、一份记录列表（[CampusDeviceEntry.kind] 区分）：
 * 逻辑完全同构（记录 / 重命名 / 图标 / 删除），拆成三个文件只会复制三份一模一样的代码。
 */
@Singleton
class CampusDeviceHubRepository @Inject constructor(
    @Named("CampusDeviceHub") private val dataStore: DataStore<Preferences>,
) {

    private object Keys {
        val ENTRIES = stringPreferencesKey("entries")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 全部记录（最近使用的在前）。 */
    val entries: Flow<List<CampusDeviceEntry>> = dataStore.data.map { preferences ->
        decode(preferences[Keys.ENTRIES])
    }

    /** 某一类设备的记录流（列表页按类型各取所需）。 */
    fun entriesOf(kind: CampusDeviceKind): Flow<List<CampusDeviceEntry>> =
        entries.map { list -> list.filter { it.kind == kind } }

    /**
     * 记一次使用（真正成功用上之后调用；失败尝试不留记录）。
     *
     * 同一台设备（同 kind + key）只保留一条记录：更新时间与次数，保留别名与图标。
     * [entry] 里给出的名称字段**只在记录里还没有时**才落下去 —— 免得某次接口没给名字
     * 就把已补全的名字清掉。
     *
     * 序号：首次落库且没有任何可读名（alias / storeName 都空）时，按同类型 max(seq)+1 发号；
     * 老记录（发号机制之前建的）seq=0 且依旧没名字，也会在这里补上 —— 「洗衣机 1」
     * 由此而来。已有名字的记录永远不发号（用户看得到店铺名，序号没有意义）。
     *
     * @return 记录后的完整条目（供调用方追加信息 / 建快捷方式）。
     */
    suspend fun record(entry: CampusDeviceEntry): CampusDeviceEntry {
        val now = System.currentTimeMillis()
        var saved = entry
        dataStore.edit { preferences ->
            val list = decode(preferences[Keys.ENTRIES])
            val merged = mergeForRecord(list, entry, now)
            saved = merged
            val updated = (list.filterNot { it.kind == merged.kind && it.key == merged.key } + merged)
                .sortedByDescending { it.lastUsedAt }
                .take(MAX_ENTRIES)
            preferences[Keys.ENTRIES] = json.encodeToString(updated)
        }
        return saved
    }

    /** 只补名称信息，不增加使用次数（后台补全设备名时用）。 */
    suspend fun fillNames(
        kind: CampusDeviceKind,
        key: String,
        storeName: String? = null,
        subjectName: String? = null,
        deviceNo: String? = null,
    ) {
        update(kind, key) { current ->
            mergeForFillNames(current, key, storeName, subjectName, deviceNo)
        }
    }

    suspend fun rename(kind: CampusDeviceKind, key: String, alias: String?) {
        update(kind, key) { it.copy(alias = alias?.trim()?.takeIf { value -> value.isNotBlank() }) }
    }

    suspend fun setIcon(kind: CampusDeviceKind, key: String, iconId: String?) {
        update(kind, key) { it.copy(iconId = iconId) }
    }

    /**
     * 改一台洗浴设备的类型（1 栋 ⇄ 2-3 栋）。
     *
     * 类型写在 key 里（`system:code[:port]`），所以「改类型」= **换 key**：这里负责把旧记录
     * 搬到新 key 上，并处理「新 key 已经有一条记录」的撞键情形（合并次数与时间，名字 / 图标
     * 优先保留目标记录已有的）。[target] 已由 [com.shiro.classflow.data.network.wbu.ShowerManualCode.normalize]
     * 校验过，调用方不需要再检查「1 栋能不能收下这串数字」。
     *
     * @return 改完之后的记录（不存在时返回 null），供调用方刷新桌面快捷方式。
     */
    suspend fun changeShowerSystem(oldKey: String, target: ShowerManualCode.Candidate): CampusDeviceEntry? {
        var updated: CampusDeviceEntry? = null
        dataStore.edit { preferences ->
            val list = decode(preferences[Keys.ENTRIES])
            val next = applySystemChange(list, oldKey, target)
            preferences[Keys.ENTRIES] = json.encodeToString(next)
            updated = next.firstOrNull {
                it.kind == CampusDeviceKind.SHOWER &&
                    it.key == showerKey(target.system, target.code, target.port)
            }
        }
        return updated
    }

    suspend fun delete(kind: CampusDeviceKind, key: String) {
        dataStore.edit { preferences ->
            val list = decode(preferences[Keys.ENTRIES])
                .filterNot { it.kind == kind && it.key == key }
            preferences[Keys.ENTRIES] = json.encodeToString(list)
        }
    }

    private suspend fun update(
        kind: CampusDeviceKind,
        key: String,
        transform: (CampusDeviceEntry) -> CampusDeviceEntry,
    ) {
        dataStore.edit { preferences ->
            val list = decode(preferences[Keys.ENTRIES]).map {
                if (it.kind == kind && it.key == key) transform(it) else it
            }
            preferences[Keys.ENTRIES] = json.encodeToString(list)
        }
    }

    private fun decode(raw: String?): List<CampusDeviceEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<CampusDeviceEntry>>(raw) }
            .getOrElse {
                Log.w(TAG, "校园设备记录解析失败，按空处理", it)
                emptyList()
            }
    }

    companion object {
        private const val TAG = "CampusDeviceHubRepo"

        /** 记录上限（三类设备合计）：够用又不至于把 DataStore 写胖。 */
        private const val MAX_ENTRIES = 120

        /**
         * [deviceNo] 是不是老代码塞进去的占位值（uuid 尾号），而不是真正的机号。
         *
         * 旧 `washerEntry` 会写 `deviceNo = key.takeLast(8)`（如 `40004678`），
         * 而真正的机号是纯数字短号（`000002`）。两者的可见区别：占位值恰好等于 key 的尾 8 位。
         * 这里只认这一条，语义最窄，不会误伤真机号。
         */
        fun isPlaceholderDeviceNo(key: String, deviceNo: String?): Boolean {
            if (deviceNo.isNullOrBlank() || key.length < 8) return false
            return deviceNo == key.takeLast(8)
        }

        /**
         * [record] 的合并规则（抽成纯函数便于单测，不含 DataStore）。
         *
         * 同一台设备（同 kind + key）只留一条：次数 +1、刷新时间，别名 / 图标 / 已补全的名字都保留。
         * 序号只发给洗衣机：扫码拿不到可读名，uuid 也不可读，才需要「洗衣机 N」兜底。
         */
        fun mergeForRecord(
            list: List<CampusDeviceEntry>,
            entry: CampusDeviceEntry,
            now: Long,
        ): CampusDeviceEntry {
            val existing = list.firstOrNull { it.kind == entry.kind && it.key == entry.key }
            val needsSeq = entry.kind == CampusDeviceKind.WASHER &&
                (existing == null || existing.seq == 0) &&
                entry.alias.isNullOrBlank() &&
                existing?.alias.isNullOrBlank() &&
                existing?.storeName.isNullOrBlank() &&
                entry.storeName.isNullOrBlank()
            val nextSeq = if (needsSeq) {
                (list.filter { it.kind == entry.kind }.maxOfOrNull { it.seq } ?: 0) + 1
            } else 0
            return (existing ?: entry).copy(
                raw = entry.raw.ifBlank { existing?.raw ?: "" },
                system = existing?.system ?: entry.system,
                port = existing?.port ?: entry.port,
                storeName = existing?.storeName ?: entry.storeName,
                subjectName = existing?.subjectName ?: entry.subjectName,
                deviceNo = existing?.deviceNo ?: entry.deviceNo,
                seq = existing?.seq?.takeIf { it > 0 }
                    ?: entry.seq.takeIf { it > 0 }
                    ?: nextSeq,
                useCount = (existing?.useCount ?: 0) + 1,
                lastUsedAt = now,
            )
        }

        /**
         * [fillNames] 的合并规则（纯函数）：只补空缺，**不增加使用次数**。
         *
         * 唯一的例外是「占位机号」：老版本把 uuid 尾号写进 deviceNo 过，那种值必须允许
         * 被真正的机号覆盖，否则存量记录永远修不好（见 [isPlaceholderDeviceNo]）。
         */
        fun mergeForFillNames(
            current: CampusDeviceEntry,
            key: String,
            storeName: String?,
            subjectName: String?,
            deviceNo: String?,
        ): CampusDeviceEntry = current.copy(
            storeName = current.storeName ?: storeName?.takeIf { it.isNotBlank() },
            subjectName = current.subjectName ?: subjectName?.takeIf { it.isNotBlank() },
            deviceNo = when {
                current.deviceNo.isNullOrBlank() -> deviceNo?.takeIf { it.isNotBlank() }
                isPlaceholderDeviceNo(key, current.deviceNo) -> deviceNo?.takeIf { it.isNotBlank() }
                else -> current.deviceNo
            },
        )

        /**
         * 洗浴记录的 key：`system:code[:port]`。
         * `code` 本身可能是个 URL（协议允许），用冒号拼接仍可安全往返 ——
         * [CampusDeviceEntry.code] 取第一段冒号后的第一段。
         */
        fun showerKey(system: String, code: String, port: String? = null): String =
            buildString {
                append(system)
                append(':')
                append(code)
                if (!port.isNullOrBlank()) {
                    append(':')
                    append(port)
                }
            }

        /**
         * 「改洗浴设备类型」的纯函数（[changeShowerSystem] 用，便于单测）。
         *
         * 类型写在 key 里，所以改类型就是**换 key**：
         * - 旧 key 不存在 → 原样返回；
         * - 新 key 已被另一条记录占用 → 合并成一条（次数相加、时间取较近，名字 / 图标保留
         *   目标记录已有的），不留下两条指向同一台设备的记录；
         * - [CampusDeviceEntry.raw] 是**旧系统**的扫码原文，换系统后必须清掉，否则记录里
         *   留着一条指向别的系统的链接；
         * - [CampusDeviceEntry.storeName] 也是旧系统给的（1 栋 `CheckKsPos` 的位置名，
         *   2-3 栋根本没有），不清掉的话换到 2-3 栋后标题还在说「马影河宿舍1栋」；
         * - [CampusDeviceEntry.deviceNo] 对洗浴一直只是 code 的镜像，跟着 code 走，
         *   否则卡片标题会显示上一个系统的号码。
         */
        fun applySystemChange(
            list: List<CampusDeviceEntry>,
            oldKey: String,
            target: ShowerManualCode.Candidate,
        ): List<CampusDeviceEntry> {
            val old = list.firstOrNull { it.kind == CampusDeviceKind.SHOWER && it.key == oldKey }
                ?: return list
            val newKey = showerKey(target.system, target.code, target.port)
            if (newKey == oldKey) {
                return list.map {
                    if (it.kind == CampusDeviceKind.SHOWER && it.key == oldKey) {
                        it.copy(system = target.system, port = target.port)
                    } else {
                        it
                    }
                }
            }

            val oldCode = old.code
            // 只有「换系统」才需要清掉旧系统的位置名 / 扫码原文；同一系统内改端口不算
            val systemChanged = old.showerSystem != target.system
            val moved = old.copy(
                key = newKey,
                system = target.system,
                port = target.port,
                raw = if (systemChanged) "" else old.raw,
                // 旧系统给的位置名对新系统没有意义；用户起的名字在 alias 里，不受影响
                storeName = if (systemChanged) null else old.storeName,
                deviceNo = when {
                    old.deviceNo.isNullOrBlank() -> target.code
                    old.deviceNo == oldCode -> target.code
                    else -> old.deviceNo
                },
            )
            val existing = list.firstOrNull { it.kind == CampusDeviceKind.SHOWER && it.key == newKey }
            val merged = if (existing == null) {
                moved
            } else {
                existing.copy(
                    port = target.port ?: existing.port,
                    useCount = existing.useCount + moved.useCount,
                    lastUsedAt = maxOf(existing.lastUsedAt, moved.lastUsedAt),
                )
            }
            return (list.filterNot {
                it.kind == CampusDeviceKind.SHOWER && (it.key == oldKey || it.key == newKey)
            } + merged).sortedByDescending { it.lastUsedAt }
        }

        /** 从洗浴 key 还原深链三要素。 */
        fun showerParts(key: String): Triple<String, String, String?> {
            val system = key.substringBefore(':')
            val rest = key.substringAfter(':')
            val code = rest.substringBefore(':')
            val port = rest.substringAfter(':', "").takeIf { it.isNotBlank() }
            val normalizedSystem = when (system) {
                CampusShowerLink.SYSTEM_YKT, CampusShowerLink.SYSTEM_LIFE -> system
                // 记录里的系统只可能是 y / l（手动输入时由用户勾选、归一后落库）。
                // 真读到别的值（旧数据 / 手改）按 1 栋回落，与改动前一致。
                else -> CampusShowerLink.SYSTEM_YKT
            }
            return Triple(normalizedSystem, code, port)
        }
    }
}
