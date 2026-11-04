package com.shiro.classflow.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [WbuUjingClient.WasherDeviceInfo] 的字段映射（实测 2026-11-04）。
 *
 * 标题只用纯机号 [WbuUjingClient.WasherDeviceInfo.deviceNo]，型号名（10kg滚筒机）
 * 刻意不进标题 —— 这里钉死「两者不会拼在一起」。
 */
class WasherDeviceInfoTest {

    @Test
    fun `device number stays a bare machine number`() {
        val info = WbuUjingClient.WasherDeviceInfo(
            deviceId = "a0373a3958c56e17edde8a267466fdea",
            deviceNo = "000002",
            deviceTypeName = "10kg滚筒机",
            storeName = "南B-11",
            serviceSubjectName = "武汉商学院-南"
        )
        assertEquals("000002", info.deviceNo)
        assertEquals("10kg滚筒机", info.deviceTypeName)
        assertEquals("南B-11", info.storeName)
        assertEquals("武汉商学院-南", info.serviceSubjectName)
    }

    @Test
    fun `missing fields stay null`() {
        val info = WbuUjingClient.WasherDeviceInfo(
            deviceId = "d",
            deviceNo = null,
            deviceTypeName = null,
            storeName = null,
            serviceSubjectName = null
        )
        assertEquals(null, info.deviceNo)
        assertEquals(null, info.storeName)
    }
}
