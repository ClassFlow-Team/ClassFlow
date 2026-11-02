package com.xingheyuzhuan.shiguangschedule.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateSourceResolutionTest {

    @Test
    fun customUrlUsesApi() {
        assertEquals(UpdateSource.API, resolveUpdateSource("https://example.com/update", ""))
    }

    @Test
    fun buildUrlUsesApi() {
        assertEquals(UpdateSource.API, resolveUpdateSource(null, "https://example.com/update"))
        assertEquals(UpdateSource.API, resolveUpdateSource("", "https://example.com/update"))
    }

    @Test
    fun noUrlFallsBackToGitHub() {
        assertEquals(UpdateSource.GITHUB, resolveUpdateSource(null, ""))
        assertEquals(UpdateSource.GITHUB, resolveUpdateSource("   ", "   "))
    }
}
