package com.shiro.classflow.data.model.wbu

import com.shiro.classflow.data.network.wbu.CampusShowerLink
import kotlinx.serialization.Serializable

/**
 * 「校园设备管理」覆盖的三类设备（吹风机不在此列：它有自己的判型体系与独立页面）。
 *
 * 三类设备的「设备身份」字段不同：
 * - 饮水机：U净 设备码 `cd`；
 * - 洗衣机：小天鹅 `uuid`；
 * - 洗浴：`system + code[+port]`（1 栋智能控水 / 2-3 栋 lifeService）。
 *
 * 统一用 [key]（同类内唯一）当记录键，跨类型不会撞（记录按 kind 分组存放，key 只在组内判重）。
 */
@Serializable
enum class CampusDeviceKind {
    /** U净 饮水机（`/w/{cd}`、`q.ujing.com.cn/ed/`）。 */
    WATER,

    /** U净 洗衣机 / 烘干机（小天鹅 `uuid`）。 */
    WASHER,

    /** 马影河洗浴控水器（1 栋 yktxyyy / 2-3 栋 lifeService）。 */
    SHOWER;

    companion object {
        fun fromNameOrNull(value: String?): CampusDeviceKind? =
            entries.firstOrNull { it.name == value }
    }
}

/**
 * 一条校园设备使用记录（**只存本机**，不参与 WebDAV 同步，与吹风机记录同一待遇）。
 *
 * 显示规则：大标题 = [alias] ?: [storeName] + [machineLabel] ?: [defaultTitle]，
 * 小标题 = [subjectName]（洗浴另外补设备号）。机号的来源**按类型不同**，见 [machineLabel]。
 */
@Serializable
data class CampusDeviceEntry(
    /** 设备类型。 */
    val kind: CampusDeviceKind,
    /** 同类内唯一的设备标识（饮水机 cd / 洗衣机 uuid / 洗浴 `system:code[:port]`）。 */
    val key: String,
    /** 扫码原文（逐字保留：洗衣机直达、洗浴深链都要求原样回传）。 */
    val raw: String = "",
    /** 洗浴专用：`y`（1 栋）或 `l`（2-3 栋），见 `CampusShowerLink`。 */
    val system: String? = null,
    /** 洗浴专用：多路控水器端口。 */
    val port: String? = null,
    /** 店铺名 / 取水点名（大标题来源之一）。 */
    val storeName: String? = null,
    /** 服务主体名（小标题，如「武汉商学院26-本部」）。 */
    val subjectName: String? = null,
    /** 设备编号（控制盒编号 / 水表号等，小标题来源之一）。 */
    val deviceNo: String? = null,
    /** 用户重命名，优先级最高。 */
    val alias: String? = null,
    /** 桌面快捷方式图标（预设 ID，见 `CampusDeviceShortcuts`）。 */
    val iconId: String? = null,
    /**
     * 同类型内的序号（1 起）：没有任何名字可用时的默认标题来源（「洗衣机 1」）。
     *
     * 接口拿不到洗衣机这类设备的可读名（实测 `scanWasherCode` 无名字字段），
     * 设备号又是一串不可读的 uuid —— 不如按「我扫过的第 N 台」起名。
     * 序号在首次落库时分配并**存在记录里**，不随删除 / 排序漂移。
     */
    val seq: Int = 0,
    val useCount: Int = 1,
    /** 上次使用时间（毫秒）。 */
    val lastUsedAt: Long = 0L,
) {
    /** 列表大标题：用户起的名字 → 店铺名 + 机号 → 类型默认名。 */
    val displayTitle: String
        get() = alias?.takeIf { it.isNotBlank() }
            ?: storeName?.takeIf { it.isNotBlank() }?.let { store ->
                machineLabel?.let { "$store-$it" } ?: store
            }
            ?: defaultTitle

    /**
     * 标题里用的「机号」，三类设备来源不同（实测 2026-11-04）：
     * - 洗衣机：详情接口的 `deviceNo`（`000002`）。扫码只给 deviceId，名字靠后台补名，
     *   所以补名之前这里是 null，标题落到 [defaultTitle] 的序号兜底。
     * - 饮水机：U净 没有「这台机器」的可读编号，`waterOrderDetail.deviceNo` 是**取水点水表号**
     *   （同一层楼的几台机器会全一样，实测两条不同 cd 都是 `000006`）。改用 cd 尾 4 位去前导零，
     *   `…0940` → `940`，稳定且能区分。
     * - 洗浴：楼栋名已经说明了位置（「马影河宿舍1栋」），再拼机号是噪音，恒为 null。
     */
    val machineLabel: String?
        get() = when (kind) {
            CampusDeviceKind.WASHER -> shortDeviceNo
            CampusDeviceKind.WATER -> waterCdLabel
            CampusDeviceKind.SHOWER -> null
        }

    /** 饮水机 cd 尾 4 位去前导零（`0011202004140940` → `940`）。 */
    private val waterCdLabel: String?
        get() {
            val tail = key.filter { it.isDigit() }.takeLast(4)
            if (tail.length < 4) return null
            return tail.trimStart('0').ifBlank { tail }
        }

    /**
     * 没有任何名字可用时的兜底标题（按类型给一个可读的默认）。
     *
     * 有序号时排在有店铺名之后、裸设备码之前：`洗衣机 1` 比 uuid 尾巴可读得多。
     * 洗浴不参与序号（楼栋 + 设备号本身就够唯一）。
     */
    val defaultTitle: String
        get() = when (kind) {
            CampusDeviceKind.WATER ->
                machineLabel?.let { "饮水机-$it" }
                    ?: key
            CampusDeviceKind.WASHER ->
                machineLabel?.let { "洗衣机-$it" }
                    ?: seqTitle()
                    ?: key.takeLast(8).ifBlank { key }
            CampusDeviceKind.SHOWER ->
                listOfNotNull(
                    systemLabel,
                    deviceNo?.takeIf { it.isNotBlank() } ?: code
                ).joinToString(" ")
        }

    /** 「饮水机 1」式兜底名（无序号时 null）。 */
    private fun seqTitle(): String? =
        seq.takeIf { it > 0 }?.let { "${kindLabel(kind)} $it" }

    /** 类型中文名（默认标题用）。 */
    private fun kindLabel(kind: CampusDeviceKind): String = when (kind) {
        CampusDeviceKind.WATER -> "饮水机"
        CampusDeviceKind.WASHER -> "洗衣机"
        CampusDeviceKind.SHOWER -> "洗浴"
    }

    /** 洗浴楼栋标签（非洗浴记录为 null）。 */
    val systemLabel: String?
        get() = when (system) {
            CampusShowerLink.SYSTEM_YKT -> "1栋"
            CampusShowerLink.SYSTEM_LIFE -> "2-3栋"
            else -> null
        }

    /** 洗浴的设备号段（`system:code[:port]` 里的 code）。 */
    val code: String
        get() = key.substringAfter(':').substringBefore(':')

    /**
     * [deviceNo] 的短显示形式：数值 ≤99 补零两位（`000002` → 「02」），>99 显示完整（`000123` → 「123」）。
     *
     * 只对纯数字机号生效（实测洗衣机详情接口 `deviceNo`「000002」）；
     * 非纯数字（或没有 [deviceNo]）返回 null，标题回落到不带机号的形式。
     */
    val shortDeviceNo: String?
        get() = deviceNo?.takeIf { it.isNotBlank() && it.matches(Regex("\\d+")) }?.let { no ->
            val value = no.toLongOrNull() ?: return@let no
            // ≤99 补零两位（000002→02）；>99 显示数值本身（000123→123，去前导零）
            if (value in 0..99) value.toString().padStart(2, '0') else value.toString()
        }

    /** 列表小标题：服务主体（洗浴没有主体名，退回设备号）；都没有就留空。 */
    val displaySubtitle: String?
        get() = subjectName?.takeIf { it.isNotBlank() }
            ?: when (kind) {
                // 洗浴的标题是楼栋 + 机号，小标题补一个原始设备号便于核对
                CampusDeviceKind.SHOWER -> deviceNo?.takeIf { it.isNotBlank() }
                else -> null
            }
}
