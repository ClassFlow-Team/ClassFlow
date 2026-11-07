package com.shiro.classflow.data.network.wbu

import com.shiro.classflow.data.model.wbu.CAMPUS_HGH_UUID
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot

/**
 * 马影河校区作息修正（教务数据 → 真实作息）。
 *
 * 背景：教务系统给马影河校区返回的第 4 节下课时间是 **12:20**，而实际作息是 **12:00**。
 * 后官湖校区的默认模板同样是「11:15 - 12:00」，所以**不能只靠时间签名**判断校区，
 * 否则会把别的校区改坏；但老用户 / WebView 导入路径拿不到校区代码，只能退回时间签名。
 *
 * ## 判定规则（两项缺一不可）
 * 1. 存在第 4 节，且 `endTime == [SECTION4_END_FROM]`（教务返回值）；
 * 2. 校区**不是已知的后官湖**。校区未知时放行（老数据 / WebView 旧脚本拿不到 `xqdm`），
 *    因为「12:20 下课」只有马影河有，误判风险远小于漏判。
 *
 * ## 红线
 * - 只改**第 4 节的下课时间**：开始时间、其它节次、顺序、`courseTableId`、`alias` 全部原样保留。
 *   教务的「上课时间」是对的，错的只有那一个下课时刻，多改一格都会把本来正确的信息弄错。
 * - 修正值只在这里定义一次（[SECTION4_END_FROM] / [SECTION4_END_TO]）；
 *   如果哪天学校把作息改回来，只需交换这两个常量，判定语义、文案、单测都不用动。
 * - 本文件**不含任何 Android 依赖**（prefs 读写见 [WbuAuthTransport]），以便 JVM 单测。
 */
object MyhTimeSlotCorrection {

    /** 教务系统返回的第 4 节下课时间（触发条件）。 */
    const val SECTION4_END_FROM: String = "12:20"

    /** 真实作息的第 4 节下课时间（修正目标）。 */
    const val SECTION4_END_TO: String = "12:00"

    /** 第 4 节的节次编号。 */
    const val SECTION4_NUMBER: Int = 4

    /** 修正动作：只描述「哪个节次的哪个字段从什么改成什么」。 */
    data class Fix(
        val section: Int = SECTION4_NUMBER,
        val fromEnd: String = SECTION4_END_FROM,
        val toEnd: String = SECTION4_END_TO
    )

    /**
     * 找出需要修正的节次；没有命中返回 null。
     *
     * @param campusId 教务解析出的校区代码（`xqdm`）。为空表示未知 —— 老数据与 WebView 导入路径没有这个值。
     */
    fun detect(timeSlots: List<TimeSlot>?, campusId: String?): Fix? {
        if (timeSlots.isNullOrEmpty()) return null
        // 已知是后官湖就一定不改：它的默认模板同样是 11:15-12:00，只靠时间签名分不出南北校区。
        // 反过来，认不出校区时**不能**直接放弃 —— 老数据和 WebView 旧脚本都拿不到 xqdm，
        // 而学校只有这两个校区（12:20 下课是马影河独有），所以只把「已知不是马影河」当否决条件。
        if (isKnownNonMayinghe(campusId)) return null
        val slot = timeSlots.firstOrNull { it.number == SECTION4_NUMBER } ?: return null
        if (slot.endTime.trim() != SECTION4_END_FROM) return null
        return Fix()
    }

    /** 明确「不是马影河」的校区代码：后官湖的 UUID 与它在教室接口里的两个别名。 */
    private fun isKnownNonMayinghe(campusId: String?): Boolean {
        if (campusId.isNullOrBlank()) return false
        return campusId.equals(CAMPUS_HGH_UUID, ignoreCase = true) ||
            campusId.equals("1", ignoreCase = true) ||
            campusId.equals("HGH", ignoreCase = true)
    }

    /**
     * 应用修正：只替换第 4 节的 `endTime`，返回新列表（原列表不被修改）。
     *
     * 目标节次不存在时返回 null，调用方据此跳过写入，避免把「无事可做」当成「已修正」。
     */
    fun apply(timeSlots: List<TimeSlot>?, fix: Fix, tableId: String): List<TimeSlot>? {
        if (timeSlots.isNullOrEmpty()) return null
        if (timeSlots.none { it.number == fix.section }) return null
        return timeSlots.map { slot ->
            if (slot.number == fix.section && slot.endTime.trim() == fix.fromEnd) {
                slot.copy(endTime = fix.toEnd, courseTableId = tableId)
            } else {
                slot.copy(courseTableId = tableId)
            }
        }.sortedBy { it.number }
    }

    /** 一次导入审计要做什么。 */
    sealed interface Decision {
        /** 不需要提示，也不改动。 */
        data object None : Decision

        /** 直接按 [Fix] 修正，不打扰用户（「总是自动修正」档）。 */
        data class SilentFix(val fix: Fix) : Decision

        /** 询问用户（「每次导入都询问」档）。 */
        data class Ask(val fix: Fix) : Decision
    }

    /** 按三档设置决定行为；没有命中返回 [Decision.None]。 */
    fun decide(mode: MyhTimeFixMode, fix: Fix?): Decision = when {
        fix == null -> Decision.None
        mode == MyhTimeFixMode.NEVER_FIX -> Decision.None
        mode == MyhTimeFixMode.ALWAYS_FIX -> Decision.SilentFix(fix)
        else -> Decision.Ask(fix)
    }

    /**
     * 用户勾了「不再提示」之后，档位跟着他这次的选择走：
     * 选了修正 → 以后自动修正；选了保持 → 以后都不处理。
     *
     * 没勾「不再提示」则保持原档位（[MyhTimeFixMode.ASK]）。
     */
    fun modeAfterPrompt(
        noMorePrompts: Boolean,
        applied: Boolean,
        current: MyhTimeFixMode
    ): MyhTimeFixMode = when {
        !noMorePrompts -> current
        applied -> MyhTimeFixMode.ALWAYS_FIX
        else -> MyhTimeFixMode.NEVER_FIX
    }
}

/** 马影河作息修正的三档策略（账号与凭据页底部折叠区）。 */
enum class MyhTimeFixMode(val storageValue: String) {
    /** 每次导入都询问（默认）。 */
    ASK("ask"),

    /** 总是自动修正，不再提示。 */
    ALWAYS_FIX("always"),

    /** 不处理，保留教务给的时间。 */
    NEVER_FIX("never");

    companion object {
        fun fromStorage(raw: String?): MyhTimeFixMode =
            entries.firstOrNull { it.storageValue == raw } ?: ASK
    }
}
