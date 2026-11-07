package com.shiro.classflow.data.model.wbu

import com.shiro.classflow.data.network.wbu.CampusShowerLink
import com.shiro.classflow.data.network.wbu.ShowerManualCode
import com.xingheyuzhuan.shiguangschedule.data.repository.CampusDeviceHubRepository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CampusDeviceEntry] 的显示规则、洗浴 key 往返，以及**从真实写入路径到标题**的合并行为。
 *
 * 命名规则（用户 2026-11-04 确认）：
 * - 洗衣机：`店铺名-机号`（详情接口 `deviceNo=000002` → `南B-11-02`）
 * - 饮水机：`店铺名-cd 尾 4 位`（`…0940` → `南B-11-940`），**不用**水表号
 * - 洗浴：只显示楼栋名，不拼机号
 */
class CampusDeviceEntryTest {

    // ---------- 大标题优先级 ----------

    @Test
    fun `title priority alias then machine label then default`() {
        val base = CampusDeviceEntry(kind = CampusDeviceKind.WATER, key = "0011202004140940")
        // 无店铺名：兜底是「类型-机号」
        assertEquals("饮水机-940", base.displayTitle)

        val withStore = base.copy(storeName = "图书馆一楼")
        assertEquals("图书馆一楼-940", withStore.displayTitle)

        val withAlias = withStore.copy(alias = "常打的那个")
        assertEquals("常打的那个", withAlias.displayTitle)
    }

    @Test
    fun `blank alias falls back to store name`() {
        val entry = CampusDeviceEntry(
            kind = CampusDeviceKind.WATER,
            key = "0011202004140940",
            storeName = "南B-11",
            alias = "   ",
        )
        assertEquals("南B-11-940", entry.displayTitle)
    }

    // ---------- 洗衣机：真实链路（washerEntry → record → fillNames → 标题） ----------

    @Test
    fun `washer entry does not preset a fake device number`() {
        val key = "0000000000000A1234567202208040004678"
        val entry = com.shiro.classflow.data.network.wbu.WasherEntryResolver.washerEntry(
            key = key,
            raw = "http://app.littleswan.com/u_download.html?type=Ujing&uuid=$key",
        )
        // 回归点：以前这里预置 deviceNo = key.takeLast(8)，把真机号永远挡住了
        assertNull(entry.deviceNo)
        assertEquals(key, entry.key)
    }

    @Test
    fun `washer title becomes store plus machine number after backfill`() {
        val key = "0000000000000A1234567202208040004678"
        val entry = com.shiro.classflow.data.network.wbu.WasherEntryResolver.washerEntry(key, "")
        // 1. 第一次使用：还没有名字，走序号兜底
        val recorded = CampusDeviceHubRepository.mergeForRecord(emptyList(), entry, 1_000L)
        assertEquals(1, recorded.seq)
        assertEquals("洗衣机 1", recorded.displayTitle)

        // 2. 后台补名：详情接口给的店名 + 纯机号
        val filled = CampusDeviceHubRepository.mergeForFillNames(
            current = recorded,
            key = key,
            storeName = "南B-11",
            subjectName = "武汉商学院-南",
            deviceNo = "000002",
        )
        assertEquals("南B-11-02", filled.displayTitle)
        assertEquals("武汉商学院-南", filled.displaySubtitle)
    }

    @Test
    fun `legacy placeholder device number gets overwritten by the real one`() {
        val key = "0000000000000A1234567202208040004678"
        // 真机上现存的那条：deviceNo 被老代码写成了 uuid 尾号
        val legacy = CampusDeviceEntry(
            kind = CampusDeviceKind.WASHER,
            key = key,
            storeName = "南B-11",
            subjectName = "武汉商学院-南",
            deviceNo = "40004678",
            seq = 1,
            useCount = 5,
        )
        assertEquals("南B-11-40004678", legacy.displayTitle)

        assertTrue(CampusDeviceHubRepository.isPlaceholderDeviceNo(key, "40004678"))
        assertFalse(CampusDeviceHubRepository.isPlaceholderDeviceNo(key, "000002"))

        val fixed = CampusDeviceHubRepository.mergeForFillNames(
            current = legacy,
            key = key,
            storeName = "南B-11",
            subjectName = "武汉商学院-南",
            deviceNo = "000002",
        )
        assertEquals("南B-11-02", fixed.displayTitle)
        // 次数不受补名影响
        assertEquals(5, fixed.useCount)
    }

    @Test
    fun `real machine number is never overwritten by another backfill`() {
        val key = "0000000000000A1234567202208040004678"
        val current = CampusDeviceEntry(
            kind = CampusDeviceKind.WASHER,
            key = key,
            storeName = "南B-11",
            deviceNo = "000002",
        )
        val again = CampusDeviceHubRepository.mergeForFillNames(
            current = current,
            key = key,
            storeName = "另一个店",
            subjectName = null,
            deviceNo = "000999",
        )
        assertEquals("000002", again.deviceNo)
        assertEquals("南B-11", again.storeName)
    }

    @Test
    fun `washer default title falls back to seq without names`() {
        val entry = CampusDeviceEntry(
            kind = CampusDeviceKind.WASHER,
            key = "8e4028ac6f2e4b2ea89c14b52e33fa8a",
            seq = 2,
        )
        assertEquals("洗衣机 2", entry.displayTitle)

        // 老记录（seq=0）：维持 uuid 尾巴
        assertEquals("52e33fa8a".takeLast(8), entry.copy(seq = 0).displayTitle)
    }

    // ---------- 饮水机：cd 尾号（不是水表号） ----------

    @Test
    fun `water title uses cd tail so two machines never collide`() {
        val a = CampusDeviceEntry(
            kind = CampusDeviceKind.WATER,
            key = "0011202004140940",
            storeName = "南B-11",
            subjectName = "武汉商学院-南",
        )
        val b = a.copy(key = "0011202004140472")

        // 真机上这两条曾因水表号同为 000006 而显示成完全一样
        assertEquals("南B-11-940", a.displayTitle)
        assertEquals("南B-11-472", b.displayTitle)
        assertTrue(a.displayTitle != b.displayTitle)
    }

    @Test
    fun `water subtitle ignores the shared meter number`() {
        val entry = CampusDeviceEntry(
            kind = CampusDeviceKind.WATER,
            key = "0011202004140940",
            storeName = "南B-11",
            subjectName = "武汉商学院-南",
            deviceNo = "000006", // 取水点水表号，多台机器共用
        )
        assertEquals("武汉商学院-南", entry.displaySubtitle)
        // 水表号不再参与标题
        assertEquals("南B-11-940", entry.displayTitle)
    }

    @Test
    fun `water title without store falls back to type and cd tail`() {
        val entry = CampusDeviceEntry(kind = CampusDeviceKind.WATER, key = "0011202004140940")
        assertEquals("饮水机-940", entry.displayTitle)
    }

    // ---------- 洗浴：只显示楼栋，不拼机号 ----------

    @Test
    fun `shower key roundtrip without port`() {
        val key = CampusDeviceHubRepository.showerKey(CampusShowerLink.SYSTEM_YKT, "12345")
        assertEquals("y:12345", key)

        val (system, code, port) = CampusDeviceHubRepository.showerParts(key)
        assertEquals("y", system)
        assertEquals("12345", code)
        assertNull(port)
    }

    @Test
    fun `shower key roundtrip with port`() {
        val key = CampusDeviceHubRepository.showerKey(CampusShowerLink.SYSTEM_LIFE, "865123050001234", "2")
        assertEquals("l:865123050001234:2", key)

        val (system, code, port) = CampusDeviceHubRepository.showerParts(key)
        assertEquals("l", system)
        assertEquals("865123050001234", code)
        assertEquals("2", port)
    }

    @Test
    fun `shower title carries building and device number but no machine suffix`() {
        val entry = CampusDeviceEntry(
            kind = CampusDeviceKind.SHOWER,
            key = "l:865123050001234:2",
            system = CampusShowerLink.SYSTEM_LIFE,
            port = "2",
        )
        // 端口由卡片 chip 单独显示，标题里不再重复
        assertEquals("2-3栋 865123050001234", entry.displayTitle)
    }

    @Test
    fun `shower building one title shows store name only`() {
        val entry = CampusDeviceEntry(
            kind = CampusDeviceKind.SHOWER,
            key = "y:10101",
            system = CampusShowerLink.SYSTEM_YKT,
            storeName = "马影河宿舍1栋",
            deviceNo = "10101",
        )
        // 用户选 A：楼栋名已经说明位置，不再拼「-10101」
        assertEquals("马影河宿舍1栋", entry.displayTitle)
        assertEquals("10101", entry.displaySubtitle)
    }

    @Test
    fun `shower never takes a sequence number`() {
        val list = emptyList<CampusDeviceEntry>()
        val entry = CampusDeviceEntry(
            kind = CampusDeviceKind.SHOWER,
            key = "l:17010737",
            system = CampusShowerLink.SYSTEM_LIFE,
            deviceNo = "17010737",
        )
        val recorded = CampusDeviceHubRepository.mergeForRecord(list, entry, 1_000L)
        assertEquals(0, recorded.seq)
        assertEquals("2-3栋 17010737", recorded.displayTitle)
    }

    // ---------- shortDeviceNo ----------

    @Test
    fun `shortDeviceNo pads two digits for values up to 99`() {
        assertEquals(
            "02",
            CampusDeviceEntry(kind = CampusDeviceKind.WASHER, key = "uuid", deviceNo = "000002").shortDeviceNo
        )
        assertEquals(
            "07",
            CampusDeviceEntry(kind = CampusDeviceKind.WASHER, key = "uuid", deviceNo = "7").shortDeviceNo
        )
        assertEquals(
            "99",
            CampusDeviceEntry(kind = CampusDeviceKind.WASHER, key = "uuid", deviceNo = "99").shortDeviceNo
        )
    }

    @Test
    fun `shortDeviceNo shows full number above 99`() {
        assertEquals(
            "123",
            CampusDeviceEntry(kind = CampusDeviceKind.WASHER, key = "uuid", deviceNo = "000123").shortDeviceNo
        )
        assertEquals(
            "100",
            CampusDeviceEntry(kind = CampusDeviceKind.WASHER, key = "uuid", deviceNo = "100").shortDeviceNo
        )
        assertEquals(
            "33301",
            CampusDeviceEntry(kind = CampusDeviceKind.WASHER, key = "uuid", deviceNo = "33301").shortDeviceNo
        )
    }

    @Test
    fun `shortDeviceNo rejects non numeric deviceNo`() {
        assertNull(
            CampusDeviceEntry(kind = CampusDeviceKind.WASHER, key = "uuid", deviceNo = "40004678A").shortDeviceNo
        )
        assertNull(CampusDeviceEntry(kind = CampusDeviceKind.WASHER, key = "uuid").shortDeviceNo)
        assertNull(
            CampusDeviceEntry(kind = CampusDeviceKind.WASHER, key = "uuid", deviceNo = "  ").shortDeviceNo
        )
    }

    // ---------- 洗浴类型标签 / 改类型 ----------

    @Test
    fun `shower system falls back to key prefix for legacy records`() {
        // 老记录没写 system 字段，只能从 key 前缀还原，否则卡片上的楼栋标签会消失
        val legacy = CampusDeviceEntry(kind = CampusDeviceKind.SHOWER, key = "l:17010737")
        assertEquals(CampusShowerLink.SYSTEM_LIFE, legacy.showerSystem)
        assertEquals("2-3栋", legacy.systemLabel)

        val ykt = CampusDeviceEntry(kind = CampusDeviceKind.SHOWER, key = "y:10101")
        assertEquals(CampusShowerLink.SYSTEM_YKT, ykt.showerSystem)
        assertEquals("1栋", ykt.systemLabel)
    }

    @Test
    fun `non shower records have no system label`() {
        val water = CampusDeviceEntry(kind = CampusDeviceKind.WATER, key = "0011202004140940")
        assertNull(water.showerSystem)
        assertNull(water.systemLabel)
        assertFalse(water.canChangeShowerSystem)
    }

    @Test
    fun `can change system only when key roundtrips`() {
        val normal = CampusDeviceEntry(
            kind = CampusDeviceKind.SHOWER,
            key = "y:10101",
            system = CampusShowerLink.SYSTEM_YKT,
        )
        assertTrue(normal.canChangeShowerSystem)

        // 设备号里带冒号（旧数据把整条链接当设备号存过）：改一次就会把号码截断
        val broken = CampusDeviceEntry(
            kind = CampusDeviceKind.SHOWER,
            key = "l:https://example.com/s/l/17010737",
            system = CampusShowerLink.SYSTEM_LIFE,
        )
        assertFalse(broken.canChangeShowerSystem)
    }

    @Test
    fun `changing system moves the record to a new key`() {
        val old = CampusDeviceEntry(
            kind = CampusDeviceKind.SHOWER,
            key = "l:17010737",
            raw = "https://example.com/s/l/17010737",
            system = CampusShowerLink.SYSTEM_LIFE,
            storeName = "马影河宿舍2栋",
            deviceNo = "17010737",
            useCount = 3,
            lastUsedAt = 500L,
        )
        val moved = CampusDeviceHubRepository.applySystemChange(
            listOf(old),
            oldKey = old.key,
            target = ShowerManualCode.Candidate(CampusShowerLink.SYSTEM_YKT, "1701")
        ).single()

        assertEquals("y:1701", moved.key)
        assertEquals(CampusShowerLink.SYSTEM_YKT, moved.system)
        assertNull(moved.port)
        // raw 是旧系统的扫码原文，换系统后必须清掉
        assertEquals("", moved.raw)
        // 位置名是旧系统服务端给的，换系统后标题不能还在说「马影河宿舍2栋」
        assertNull(moved.storeName)
        // deviceNo 跟着 code 走，否则标题会显示上一个系统的号码
        assertEquals("1701", moved.deviceNo)
        assertEquals(3, moved.useCount)
        assertEquals(500L, moved.lastUsedAt)
    }

    @Test
    fun `changing only the port keeps store name and raw`() {
        val old = CampusDeviceEntry(
            kind = CampusDeviceKind.SHOWER,
            key = "l:17010737",
            raw = "https://example.com/s/l/17010737",
            system = CampusShowerLink.SYSTEM_LIFE,
            storeName = "马影河宿舍2栋",
            deviceNo = "17010737",
        )
        val moved = CampusDeviceHubRepository.applySystemChange(
            listOf(old),
            oldKey = old.key,
            target = ShowerManualCode.Candidate(CampusShowerLink.SYSTEM_LIFE, "17010737", "3")
        ).single()

        assertEquals("l:17010737:3", moved.key)
        assertEquals("3", moved.port)
        // 系统没变：位置名与扫码原文都还有效
        assertEquals("马影河宿舍2栋", moved.storeName)
        assertEquals("https://example.com/s/l/17010737", moved.raw)
    }

    @Test
    fun `changing system merges into an existing record at the new key`() {
        val source = CampusDeviceEntry(
            kind = CampusDeviceKind.SHOWER,
            key = "l:10101",
            system = CampusShowerLink.SYSTEM_LIFE,
            deviceNo = "10101",
            useCount = 2,
            lastUsedAt = 100L,
        )
        val target = CampusDeviceEntry(
            kind = CampusDeviceKind.SHOWER,
            key = "y:10101",
            system = CampusShowerLink.SYSTEM_YKT,
            deviceNo = "10101",
            alias = "一楼那个",
            useCount = 5,
            lastUsedAt = 900L,
        )
        val result = CampusDeviceHubRepository.applySystemChange(
            listOf(source, target),
            oldKey = source.key,
            target = ShowerManualCode.Candidate(CampusShowerLink.SYSTEM_YKT, "10101")
        )
        // 不能留下两条指向同一台设备的记录
        assertEquals(1, result.size)
        val merged = result.single()
        assertEquals("y:10101", merged.key)
        assertEquals(7, merged.useCount)
        assertEquals(900L, merged.lastUsedAt)
        assertEquals("一楼那个", merged.alias)
    }

    @Test
    fun `changing system with unknown old key is a no-op`() {
        val list = listOf(
            CampusDeviceEntry(kind = CampusDeviceKind.SHOWER, key = "y:10101")
        )
        val result = CampusDeviceHubRepository.applySystemChange(
            list,
            oldKey = "y:99999",
            target = ShowerManualCode.Candidate(CampusShowerLink.SYSTEM_LIFE, "123456")
        )
        assertEquals(list, result)
    }

    @Test
    fun `normalize enforces each system's rules`() {
        // 1 栋：≤5 位、纯数字、无端口、去前导零
        assertEquals(
            ShowerManualCode.Candidate(CampusShowerLink.SYSTEM_YKT, "10101"),
            ShowerManualCode.normalize(CampusShowerLink.SYSTEM_YKT, "010101", null)
        )
        assertNull(ShowerManualCode.normalize(CampusShowerLink.SYSTEM_YKT, "123456", null))
        assertNull(ShowerManualCode.normalize(CampusShowerLink.SYSTEM_YKT, "10101", "2"))
        assertNull(ShowerManualCode.normalize(CampusShowerLink.SYSTEM_YKT, "10a01", null))

        // 2-3 栋：保留原文（可带前导零）与端口
        assertEquals(
            ShowerManualCode.Candidate(CampusShowerLink.SYSTEM_LIFE, "00123456", "2"),
            ShowerManualCode.normalize(CampusShowerLink.SYSTEM_LIFE, "00123456", "2")
        )
        assertNull(ShowerManualCode.normalize(CampusShowerLink.SYSTEM_LIFE, "00123456", "abc"))
    }

    // ---------- 枚举 ----------

    @Test
    fun `kind parses by name and rejects unknown`() {
        assertEquals(CampusDeviceKind.WATER, CampusDeviceKind.fromNameOrNull("WATER"))
        assertEquals(CampusDeviceKind.SHOWER, CampusDeviceKind.fromNameOrNull("SHOWER"))
        assertNull(CampusDeviceKind.fromNameOrNull("HAIRDRYER"))
        assertNull(CampusDeviceKind.fromNameOrNull(null))
    }
}
