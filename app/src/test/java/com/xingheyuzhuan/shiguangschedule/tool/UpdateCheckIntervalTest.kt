package com.xingheyuzhuan.shiguangschedule.tool

import com.xingheyuzhuan.shiguangschedule.data.model.UpdateCheckInterval
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateCheckIntervalTest {

    @Test
    fun defaultsToDaily() {
        assertEquals(UpdateCheckInterval.DAILY, UpdateCheckInterval.fromId(null))
        assertEquals(UpdateCheckInterval.DAILY, UpdateCheckInterval.fromId("unknown"))
    }

    @Test
    fun parsesKnownIds() {
        assertEquals(UpdateCheckInterval.EVERY_LAUNCH, UpdateCheckInterval.fromId("every_launch"))
        assertEquals(UpdateCheckInterval.EVERY_6_HOURS, UpdateCheckInterval.fromId("6h"))
        assertEquals(UpdateCheckInterval.EVERY_12_HOURS, UpdateCheckInterval.fromId("12h"))
        assertEquals(UpdateCheckInterval.DAILY, UpdateCheckInterval.fromId("24h"))
        assertEquals(UpdateCheckInterval.EVERY_3_DAYS, UpdateCheckInterval.fromId("3d"))
        assertEquals(UpdateCheckInterval.EVERY_7_DAYS, UpdateCheckInterval.fromId("7d"))
    }

    @Test
    fun millisMatchHours() {
        assertEquals(0L, UpdateCheckInterval.EVERY_LAUNCH.millis)
        assertEquals(6L * 60 * 60 * 1000, UpdateCheckInterval.EVERY_6_HOURS.millis)
        assertEquals(24L * 60 * 60 * 1000, UpdateCheckInterval.DAILY.millis)
        assertEquals(7L * 24 * 60 * 60 * 1000, UpdateCheckInterval.EVERY_7_DAYS.millis)
    }
}
