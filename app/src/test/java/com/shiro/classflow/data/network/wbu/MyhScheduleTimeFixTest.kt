package com.shiro.classflow.data.network.wbu

import com.shiro.classflow.data.model.wbu.CAMPUS_HGH_UUID
import com.shiro.classflow.data.model.wbu.CAMPUS_MYH_ID
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 马影河作息修正的判据与三档行为。
 *
 * 这组断言钉住的是「宁可少改，不可误改」：后官湖的默认模板同样是 11:15-12:00，
 * 只靠时间签名会把南北校区混为一谈，所以校区已知时一律以校区为准。
 */
class MyhScheduleTimeFixTest {

    private fun slots(
        section4End: String = "12:20",
        section4Start: String = "11:15",
        tableId: String = "t1"
    ): List<TimeSlot> = listOf(
        TimeSlot(1, "08:30", "09:15", tableId),
        TimeSlot(2, "09:20", "10:05", tableId),
        TimeSlot(3, "10:25", "11:10", tableId),
        TimeSlot(4, section4Start, section4End, tableId),
        TimeSlot(5, "14:00", "14:45", tableId)
    )

    // ── detect ──

    @Test
    fun `detects section4 12-20 on mayinghe`() {
        val fix = MyhTimeSlotCorrection.detect(slots(), CAMPUS_MYH_ID)
        assertNotNull(fix)
        assertEquals(4, fix!!.section)
        assertEquals("12:20", fix.fromEnd)
        assertEquals("12:00", fix.toEnd)
    }

    @Test
    fun `detects by signature when campus unknown`() {
        // 老数据 / WebView 旧脚本拿不到 xqdm，只能按时间签名判断
        assertNotNull(MyhTimeSlotCorrection.detect(slots(), null))
        assertNotNull(MyhTimeSlotCorrection.detect(slots(), ""))
    }

    @Test
    fun `never touches houguanhu`() {
        // 后官湖默认模板同样是 11:15-12:00，不能因为时间像就改
        assertNull(MyhTimeSlotCorrection.detect(slots(), CAMPUS_HGH_UUID))
    }

    @Test
    fun `ignores any other known campus`() {
        // 认得出是后官湖（含教室接口的 1 / HGH 别名）时一律不动：宁可少改，不可误改
        assertNull(MyhTimeSlotCorrection.detect(slots(), CAMPUS_HGH_UUID))
        assertNull(MyhTimeSlotCorrection.detect(slots(), "1"))
        assertNull(MyhTimeSlotCorrection.detect(slots(), "HGH"))
    }

    @Test
    fun `accepts MYH alias`() {
        assertNotNull(MyhTimeSlotCorrection.detect(slots(), "MYH"))
        assertNotNull(MyhTimeSlotCorrection.detect(slots(), "myh"))
    }

    @Test
    fun `does not re-detect after correction`() {
        assertNull(MyhTimeSlotCorrection.detect(slots(section4End = "12:00"), CAMPUS_MYH_ID))
    }

    @Test
    fun `does not detect when section 4 is missing`() {
        val onlyEarly = slots().filterNot { it.number == 4 }
        assertNull(MyhTimeSlotCorrection.detect(onlyEarly, CAMPUS_MYH_ID))
    }

    @Test
    fun `does not detect on empty or null`() {
        assertNull(MyhTimeSlotCorrection.detect(null, CAMPUS_MYH_ID))
        assertNull(MyhTimeSlotCorrection.detect(emptyList(), CAMPUS_MYH_ID))
    }

    @Test
    fun `ignores whitespace around time`() {
        assertNotNull(MyhTimeSlotCorrection.detect(slots(section4End = " 12:20 "), CAMPUS_MYH_ID))
    }

    // ── apply ──

    @Test
    fun `apply only rewrites section4 end time`() {
        val original = slots()
        val corrected = MyhTimeSlotCorrection.apply(original, MyhTimeSlotCorrection.Fix(), "t1")
        assertNotNull(corrected)
        val s4 = corrected!!.first { it.number == 4 }
        assertEquals("11:15", s4.startTime) // 开始时间是对的，不能动
        assertEquals("12:00", s4.endTime)
        // 其它节次逐字段不变
        original.filterNot { it.number == 4 }.forEach { before ->
            assertEquals(before, corrected.first { it.number == before.number })
        }
        assertEquals(5, corrected.size)
    }

    @Test
    fun `apply keeps user alias and sorts by number`() {
        val withAlias = slots().map { if (it.number == 4) it.copy(alias = "上午第四节") else it }
        val corrected = MyhTimeSlotCorrection.apply(withAlias.reversed(), MyhTimeSlotCorrection.Fix(), "t1")
        assertNotNull(corrected)
        assertEquals(listOf(1, 2, 3, 4, 5), corrected!!.map { it.number })
        assertEquals("上午第四节", corrected.first { it.number == 4 }.alias)
    }

    @Test
    fun `apply rewrites table id`() {
        val corrected = MyhTimeSlotCorrection.apply(slots(), MyhTimeSlotCorrection.Fix(), "newTable")
        assertTrue(corrected!!.all { it.courseTableId == "newTable" })
    }

    @Test
    fun `apply returns null when nothing to do`() {
        assertNull(MyhTimeSlotCorrection.apply(null, MyhTimeSlotCorrection.Fix(), "t1"))
        assertNull(MyhTimeSlotCorrection.apply(emptyList(), MyhTimeSlotCorrection.Fix(), "t1"))
        assertNull(
            MyhTimeSlotCorrection.apply(
                slots().filterNot { it.number == 4 },
                MyhTimeSlotCorrection.Fix(),
                "t1"
            )
        )
    }

    // ── decide（三档） ──

    @Test
    fun `ask mode asks when detected`() {
        val decision = MyhTimeSlotCorrection.decide(MyhTimeFixMode.ASK, MyhTimeSlotCorrection.Fix())
        assertTrue(decision is MyhTimeSlotCorrection.Decision.Ask)
    }

    @Test
    fun `always mode fixes silently`() {
        val decision = MyhTimeSlotCorrection.decide(MyhTimeFixMode.ALWAYS_FIX, MyhTimeSlotCorrection.Fix())
        assertTrue(decision is MyhTimeSlotCorrection.Decision.SilentFix)
    }

    @Test
    fun `never mode does nothing`() {
        val decision = MyhTimeSlotCorrection.decide(MyhTimeFixMode.NEVER_FIX, MyhTimeSlotCorrection.Fix())
        assertTrue(decision is MyhTimeSlotCorrection.Decision.None)
    }

    @Test
    fun `no fix means no decision regardless of mode`() {
        MyhTimeFixMode.entries.forEach { mode ->
            assertTrue(MyhTimeSlotCorrection.decide(mode, null) is MyhTimeSlotCorrection.Decision.None)
        }
    }

    // ── 勾「不再提示」后的档位 ──

    @Test
    fun `no-more-prompts follows what user chose`() {
        // 勾了 + 改了 → 以后自动改
        assertEquals(
            MyhTimeFixMode.ALWAYS_FIX,
            MyhTimeSlotCorrection.modeAfterPrompt(noMorePrompts = true, applied = true, current = MyhTimeFixMode.ASK)
        )
        // 勾了 + 没改 → 以后都不处理
        assertEquals(
            MyhTimeFixMode.NEVER_FIX,
            MyhTimeSlotCorrection.modeAfterPrompt(noMorePrompts = true, applied = false, current = MyhTimeFixMode.ASK)
        )
    }

    @Test
    fun `without the checkbox mode stays untouched`() {
        assertEquals(
            MyhTimeFixMode.ASK,
            MyhTimeSlotCorrection.modeAfterPrompt(noMorePrompts = false, applied = true, current = MyhTimeFixMode.ASK)
        )
        assertEquals(
            MyhTimeFixMode.ASK,
            MyhTimeSlotCorrection.modeAfterPrompt(noMorePrompts = false, applied = false, current = MyhTimeFixMode.ASK)
        )
    }

    // ── 存储值往返 ──

    @Test
    fun `mode storage round trips and falls back to ask`() {
        MyhTimeFixMode.entries.forEach { mode ->
            assertEquals(mode, MyhTimeFixMode.fromStorage(mode.storageValue))
        }
        assertEquals(MyhTimeFixMode.ASK, MyhTimeFixMode.fromStorage(null))
        assertEquals(MyhTimeFixMode.ASK, MyhTimeFixMode.fromStorage(""))
        assertEquals(MyhTimeFixMode.ASK, MyhTimeFixMode.fromStorage("unknown"))
    }
}
