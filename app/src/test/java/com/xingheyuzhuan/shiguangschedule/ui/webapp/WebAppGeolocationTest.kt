package com.xingheyuzhuan.shiguangschedule.ui.webapp

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页定位桥（[WebAppGeolocationScript] / [GeoFix]）。
 *
 * 背景（真机实测）：门户里的业务系统是 http 站点，Chromium 对非安全源硬拒绝
 * `navigator.geolocation`（`Only secure origins are allowed`，且发生在权限判定之前），
 * 页面只能拿到百度 SDK 的 IP 级兜底位置（实测偏 8.5 公里、不随人走）。
 * 所以容器自己取位置，用注入脚本把 `navigator.geolocation` 换掉 —— 这里守住几件
 * 「错了就整条链路静默失效」的事：
 *
 * - 注入脚本里引用的桥名必须与 Kotlin 侧注册的名字一致（写错就是桥不在，脚本直接退出，页面
 *   乖乖回到 IP 级兜底，而且看不出任何报错）；
 * - 回执 JSON 里的数字必须带小数点且与区域设置无关（某些区域设置会写成逗号，页面
 *   `parseFloat` 拿到 `NaN`，距离比较全错）；
 * - 文案里的引号要转义，否则拼出来的 JS 回执会语法错误。
 */
class WebAppGeolocationTest {

    @Test
    fun injectedScriptRefersToTheBridgeNameTheContainerRegisters() {
        assertTrue(
            "脚本必须引用容器注册的那个桥名",
            WebAppGeolocationScript.JS.contains(WebAppGeolocationScript.BRIDGE_NAME)
        )
    }

    @Test
    fun injectedScriptOverridesTheThreeGeolocationApis() {
        val js = WebAppGeolocationScript.JS
        assertTrue(js.contains("getCurrentPosition"))
        assertTrue(js.contains("watchPosition"))
        assertTrue(js.contains("clearWatch"))
        // 回灌入口：原生侧 evaluateJavascript 调的就是这个名字
        assertTrue(js.contains("__cfGeoDeliver"))
        // 幂等：重复注入（onPageStarted / onPageFinished / 容器挂回来）不能把挂起的请求冲掉
        assertTrue(js.contains("__cfGeoShimInstalled"))
    }

    @Test
    fun fixJsonCarriesRawWgs84AndPreciseDecimals() {
        val json = GeoFix(
            latitude = 30.4659261,
            longitude = 114.0938595,
            accuracy = 25.5f,
            timeMillis = 1_700_000_000_000L,
            provider = "gps"
        ).toBridgeJson()

        assertEquals(
            "{\"ok\":true,\"latitude\":30.46592610,\"longitude\":114.09385950," +
                "\"accuracy\":25.50,\"time\":1700000000000,\"provider\":\"gps\"}",
            json
        )
    }

    @Test
    fun fixJsonUsesDotEvenUnderCommaDecimalLocales() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val json = GeoFix(30.4659261, 114.0938595, 25.5f, 1L, null).toBridgeJson()
            assertTrue("小数点必须还是点：$json", json.contains("\"latitude\":30.46592610"))
            assertTrue("逗号会毁掉页面的 parseFloat：$json", !json.contains("\"latitude\":30,46592610"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun failureJsonEscapesQuotesInMessage() {
        val json = GeoFix.failureJson(2, "拿不到 \"位置\" 信息")
        assertEquals("{\"ok\":false,\"code\":2,\"message\":\"拿不到 \\\"位置\\\" 信息\"}", json)
    }
}
