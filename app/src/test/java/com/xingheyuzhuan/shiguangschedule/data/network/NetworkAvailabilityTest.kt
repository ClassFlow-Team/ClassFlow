package com.xingheyuzhuan.shiguangschedule.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「没联网」判据的单测。
 *
 * 这里钉住的是**最烦人的一类误报**：用户明明有网（有线网卡 / Gnirehtet 反向共享 / 校园网还没过认证），
 * App 却弹一句「未连接网络」。所以判据刻意收得很窄 —— 只有「系统里一个默认网络都没有」才成立，
 * 谁要是把它改成「只认 WiFi 和蜂窝」「VALIDATED 为 true 才算有网」，这里的用例会立刻红。
 */
class NetworkAvailabilityTest {

    private fun online(vararg transports: NetworkTransport) = LinkSnapshot(
        hasActiveNetwork = true,
        hasCapabilities = true,
        transports = transports.toSet()
    )

    @Test
    fun noActiveNetworkIsOffline() {
        assertEquals(
            NetworkAvailability.State.Offline,
            decideState(LinkSnapshot(hasActiveNetwork = false, hasCapabilities = false, transports = emptySet()))
        )
    }

    @Test
    fun everyRealTransportCountsAsOnline() {
        // 有线网络：宿舍 / 实验室最常见的联网方式，绝不能判成没网
        assertEquals(NetworkAvailability.State.Online, decideState(online(NetworkTransport.Ethernet)))
        // Gnirehtet / 反向共享：系统里就是一条 VPN，而且几乎不带 VALIDATED
        assertEquals(NetworkAvailability.State.Online, decideState(online(NetworkTransport.Vpn)))
        // USB / 蓝牙网络共享
        assertEquals(NetworkAvailability.State.Online, decideState(online(NetworkTransport.Usb)))
        assertEquals(NetworkAvailability.State.Online, decideState(online(NetworkTransport.Bluetooth)))
        // 校园网 Wi-Fi（认证前 NET_CAPABILITY_INTERNET / VALIDATED 常常为 false）
        assertEquals(NetworkAvailability.State.Online, decideState(online(NetworkTransport.Wifi)))
        assertEquals(NetworkAvailability.State.Online, decideState(online(NetworkTransport.Cellular)))
        // 多传输方式叠加（WiFi + VPN 并存）同样是「有网」
        assertEquals(
            NetworkAvailability.State.Online,
            decideState(online(NetworkTransport.Wifi, NetworkTransport.Vpn))
        )
    }

    @Test
    fun switchingMomentIsNotOffline() {
        // 有默认网络但一时读不到它的能力（切换瞬间）：fail-open，绝不说「没有网络」
        assertEquals(
            NetworkAvailability.State.Online,
            decideState(LinkSnapshot(hasActiveNetwork = true, hasCapabilities = false, transports = emptySet()))
        )
        // 有网络、有能力位，但读不出传输方式：同样 fail-open
        assertEquals(NetworkAvailability.State.Online, decideState(online()))
    }

    @Test
    fun unreadableSnapshotIsNotOffline() {
        // 没有 ConnectivityManager / 读系统状态抛异常：fail-open
        assertEquals(NetworkAvailability.State.Online, decideState(null))
    }
}
