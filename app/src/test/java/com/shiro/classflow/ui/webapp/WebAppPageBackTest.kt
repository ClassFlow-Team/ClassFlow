package com.shiro.classflow.ui.webapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页容器里「页面自己的返回控件」的两处支持：
 *
 * 1. [isLifeServiceBackShimTarget] —— 2-3 栋洗浴（`/applications/lifeService`）顶栏箭头
 *    在 H5 载体下递归调自身（`back()` → `child.back()` → `$parent.back()` → …，
 *    `RangeError` 被 Vue 吞掉），需要注入返回兜底脚本；平台其它页面的返回语义正常，
 *    不注入。真机深链不带 hash（`/applications/lifeService?_implid=…`），判定只看 host + path。
 * 2. [PAGE_BACK_POP_KEY] —— `SynJSNative` 协议里「页面要求关闭自己」的主键
 *    （1 栋控水 yktxyyy:5001 的「‹ 主页」按钮发 `synjones.ecampus.window.pop`）。
 */
class WebAppPageBackTest {

    // ---------- isLifeServiceBackShimTarget ----------

    @Test
    fun lifeServiceDeepLinkWithoutHashIsTarget() {
        // 马影河 2-3 栋淋浴深链的真实形态（CampusShowerEntryResolver 拼出，无 hash）
        val url =
            "http://yktfwpt.wbu.edu.cn/applications/lifeService" +
                "?_dt=101&_implid=63_101&feeitemid=414&appId=65&loginFrom=h5&synAccessSource=h5" +
                "&synjones-auth=bearer%20eyJhbGciOiJIUzI1NiJ9.abc"
        assertTrue(isLifeServiceBackShimTarget(url))
    }

    @Test
    fun lifeServicePlainUrlIsTarget() {
        assertTrue(isLifeServiceBackShimTarget("http://yktfwpt.wbu.edu.cn/applications/lifeService"))
    }

    @Test
    fun lifeServiceUrlWithHashIsTarget() {
        assertTrue(
            isLifeServiceBackShimTarget(
                "http://yktfwpt.wbu.edu.cn/applications/lifeService?appId=65#/lifeService"
            )
        )
    }

    @Test
    fun platformHomeIsNotTarget() {
        assertFalse(
            isLifeServiceBackShimTarget("http://yktfwpt.wbu.edu.cn/plat/shouyeUser")
        )
    }

    @Test
    fun platformApplicationsRootIsNotTarget() {
        // 同一 host 下的其它扩展应用页面不注入，避免改掉它们正常的返回语义
        assertFalse(
            isLifeServiceBackShimTarget("http://yktfwpt.wbu.edu.cn/applications/otherApp?id=1")
        )
    }

    @Test
    fun similarPathIsNotTarget() {
        // 前缀相同的更长路径不能误命中
        assertFalse(
            isLifeServiceBackShimTarget("http://yktfwpt.wbu.edu.cn/applications/lifeServiceExtra")
        )
    }

    @Test
    fun otherHostIsNotTarget() {
        // 1 栋智能控水走 SynJSNative 的 window.pop 桥，不需要这个脚本
        assertFalse(isLifeServiceBackShimTarget("http://yktxyyy.wbu.edu.cn:5001/"))
    }

    @Test
    fun paymentPageIsNotTarget() {
        assertFalse(
            isLifeServiceBackShimTarget("http://yktfwpt.wbu.edu.cn/charge-app/?feeitemid=412")
        )
    }

    @Test
    fun blankAndGarbageAreNotTargets() {
        assertFalse(isLifeServiceBackShimTarget(null))
        assertFalse(isLifeServiceBackShimTarget(""))
        assertFalse(isLifeServiceBackShimTarget("   "))
        assertFalse(isLifeServiceBackShimTarget("not a url \u0020:%"))
    }

    // ---------- PAGE_BACK_POP_KEY ----------

    @Test
    fun popKeyMatchesProtocolString() {
        // 1 栋控水页面（chunk-7726）发出的原文
        assertEquals(
            "synjones.ecampus.window.pop",
            PAGE_BACK_POP_KEY
        )
    }

    @Test
    fun popKeyIsNotMistakenForScanKey() {
        // 防回归：pop 主键不能含 "scan"，否则会被既有的扫码分支抢走
        assertFalse(PAGE_BACK_POP_KEY.contains("scan", ignoreCase = true))
    }

    // ---------- isTransientBackFlowEntry ----------

    @Test
    fun redirectTicketEntryIsTransient() {
        // 深链进入各应用都要先落 berserker-base/redirect 换票据、再 302 到目标页：
        // 这条历史不算「一页」，否则返回会被 302 弹回来（表现成「要按两次」）
        assertTrue(
            isTransientBackFlowEntry(
                "http://yktfwpt.wbu.edu.cn/berserker-base/redirect?appId=62&ticket=abc"
            )
        )
    }

    @Test
    fun blankAndShowerAppPagesAreTransient() {
        assertTrue(isTransientBackFlowEntry(null))
        assertTrue(isTransientBackFlowEntry(""))
        assertTrue(isTransientBackFlowEntry("about:blank"))
        // 1 栋智能控水自身的页面（含票据参数）也是过渡条目
        assertTrue(
            isTransientBackFlowEntry(
                "http://yktxyyy.wbu.edu.cn:5001/?ticket=abc"
            )
        )
        // 2-3 栋 lifeService 自身
        assertTrue(
            isTransientBackFlowEntry(
                "http://yktfwpt.wbu.edu.cn/applications/lifeService?_implid=63_101&feeitemid=414"
            )
        )
    }

    @Test
    fun campusCardHomeIsNotTransient() {
        // 一卡通首页是「最近一个真实页面」，返回应落在这里而不是退出
        assertFalse(
            isTransientBackFlowEntry("http://yktfwpt.wbu.edu.cn/plat/shouyeUser")
        )
    }

    @Test
    fun otherPlatformPagesAreNotTransient() {
        // 缴费等平台内页是真实页面，返回落在它上面
        assertFalse(
            isTransientBackFlowEntry("http://yktfwpt.wbu.edu.cn/charge-app/?feeitemid=412")
        )
        // 平台应用表等其它 berserker 接口页也不在过渡名单里
        assertFalse(
            isTransientBackFlowEntry("http://yktfwpt.wbu.edu.cn/berserker-app/app/getAllApps")
        )
    }

    @Test
    fun externalPagesAreNotTransient() {
        // 站外页面（如 CAS / U净）不是过渡条目
        assertFalse(
            isTransientBackFlowEntry("https://ids.wbu.edu.cn/authserver/login")
        )
    }
}
