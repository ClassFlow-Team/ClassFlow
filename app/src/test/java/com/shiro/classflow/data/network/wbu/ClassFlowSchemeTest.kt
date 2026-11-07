package com.shiro.classflow.data.network.wbu

import com.xingheyuzhuan.shiguangschedule.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassFlowSchemeTest {

    private val host = BuildConfig.LINK_HUB_HOST

    @Test
    fun canonicalizesWaterLink() {
        assertEquals(
            "https://$host/w/0011202004140940",
            ClassFlowScheme.canonicalize("classflow://w/0011202004140940")
        )
    }

    @Test
    fun canonicalizesTripleSlashForm() {
        assertEquals(
            "https://$host/w/0011202004140940",
            ClassFlowScheme.canonicalize("classflow:///w/0011202004140940")
        )
    }

    @Test
    fun canonicalizesWasherAndHairdryer() {
        assertEquals(
            "https://$host/wm/0000000000000A1234567202208040004678",
            ClassFlowScheme.canonicalize("classflow://wm/0000000000000A1234567202208040004678")
        )
        assertEquals(
            "https://$host/hd/0011202004140940",
            ClassFlowScheme.canonicalize("classflow://hd/0011202004140940")
        )
    }

    @Test
    fun canonicalizesShowerLinks() {
        assertEquals("https://$host/s/y/10101", ClassFlowScheme.canonicalize("classflow://s/y/10101"))
        assertEquals("https://$host/s/l/1234567890/2", ClassFlowScheme.canonicalize("classflow://s/l/1234567890/2"))
    }

    @Test
    fun preservesQueryAndFragment() {
        assertEquals(
            "https://$host/url/ab12cd?from=nfc#ERERER",
            ClassFlowScheme.canonicalize("classflow://url/ab12cd?from=nfc#ERERER")
        )
        assertEquals(
            "https://$host/u/#ERERER",
            ClassFlowScheme.canonicalize("classflow://u/#ERERER")
        )
    }

    @Test
    fun schemeIsCaseInsensitive() {
        assertEquals("https://$host/w/abc", ClassFlowScheme.canonicalize("ClassFlow://w/abc"))
        assertTrue(ClassFlowScheme.isClassFlowUri("CLASSFLOW://w/abc"))
    }

    @Test
    fun canonicalizesAppPages() {
        assertEquals("https://$host/pay", ClassFlowScheme.canonicalize("classflow://pay"))
        // 尾斜杠原样保留（路由层会 trim），与 query / fragment 的处理保持一致
        assertEquals("https://$host/pay/", ClassFlowScheme.canonicalize("classflow://pay/"))
        assertEquals("https://$host/scan", ClassFlowScheme.canonicalize("classflow://scan"))
        // 首段统一小写
        assertEquals("https://$host/pay", ClassFlowScheme.canonicalize("CLASSFLOW://PAY"))
    }

    @Test
    fun rejectsNonClassFlowScheme() {
        assertNull(ClassFlowScheme.canonicalize("https://$host/w/abc"))
        assertNull(ClassFlowScheme.canonicalize("classflow:/w/abc"))
        assertFalse(ClassFlowScheme.isClassFlowUri("https://$host/w/abc"))
    }

    @Test
    fun rejectsUserInfoPortAndUnknownPath() {
        assertNull(ClassFlowScheme.canonicalize("classflow://user@w/abc"))
        assertNull(ClassFlowScheme.canonicalize("classflow://w:8080/abc"))
        assertNull(ClassFlowScheme.canonicalize("classflow://other/abc"))
        assertNull(ClassFlowScheme.canonicalize("classflow://"))
    }
}
