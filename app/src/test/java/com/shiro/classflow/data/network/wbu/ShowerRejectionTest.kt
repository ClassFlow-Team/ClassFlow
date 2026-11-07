package com.shiro.classflow.data.network.wbu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 业务拒绝归类单测：决定「显示服务端原话 + 给哪个跟手动作」。
 *
 * 回归的是真机上撞到的那个 bug：`msg=卡内余额不足` / `msg=设备在使用中` 被显示成
 * 「网络连接失败，请检查网络后重试」。
 */
class ShowerRejectionTest {

    @Test
    fun classifiesInsufficientBalance() {
        assertEquals(
            ShowerRejection.INSUFFICIENT_BALANCE,
            classifyShowerRejection("卡内余额不足")
        )
        assertEquals(
            ShowerRejection.INSUFFICIENT_BALANCE,
            classifyShowerRejection("余额不足，请充值")
        )
    }

    @Test
    fun classifiesDeviceInUse() {
        assertEquals(ShowerRejection.DEVICE_IN_USE, classifyShowerRejection("设备在使用中"))
    }

    @Test
    fun classifiesDeviceMismatch() {
        assertEquals(ShowerRejection.DEVICE_MISMATCH, classifyShowerRejection("设备不匹配"))
    }

    @Test
    fun unknownMessagesFallBackToOther() {
        // 匹配不上一律 OTHER：原样转述服务端文案，不按猜测编造原因
        assertEquals(ShowerRejection.OTHER, classifyShowerRejection("获取状态失败"))
        assertEquals(ShowerRejection.OTHER, classifyShowerRejection("Insufficient balance"))
        assertEquals(ShowerRejection.OTHER, classifyShowerRejection(""))
        assertEquals(ShowerRejection.OTHER, classifyShowerRejection(null))
    }

    @Test
    fun startRefusedOnlyWhenStatusSaysSoAndServerExplains() {
        // code=200 + status=1：正常开单
        assertFalse(showerStartRefused(1, "开始用水成功"))
        // status 缺失：服务端没这个字段，不能凭空判成失败
        assertFalse(showerStartRefused(null, "开始用水成功"))
        // status=1 但带了话：那只是提示，不算拒绝
        assertFalse(showerStartRefused(1, null))
        // 明确非 1 且给了原因：这才是「code=200 但其实没开成」
        assertTrue(showerStartRefused(0, "设备已被预约"))
        assertTrue(showerStartRefused(2, "设备故障中"))
        // 非 1 但一句话都没有：没有可显示的内容，按成功处理（避免弹出空错误页）
        assertFalse(showerStartRefused(0, null))
        assertFalse(showerStartRefused(0, "  "))
    }
}
