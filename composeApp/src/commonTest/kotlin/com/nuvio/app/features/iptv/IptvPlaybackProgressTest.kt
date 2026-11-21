package com.nuvio.app.features.iptv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IptvPlaybackProgressTest {
    private val m = emptyMap<String, IptvProgressEntry>()
    private val dur = 3_600_000L

    @Test fun liveIgnored() {
        assertTrue(updateIptvProgress(m, "k", 5000, 0, false).isEmpty())
    }

    @Test fun watchedThreshold() {
        assertFalse(updateIptvProgress(m, "k", (dur * 0.89).toLong(), dur, false)["k"]!!.watched)
        assertTrue(updateIptvProgress(m, "k", (dur * 0.90).toLong(), dur, false)["k"]!!.watched)
        assertTrue(updateIptvProgress(m, "k", 1000, dur, true)["k"]!!.watched)
    }

    @Test fun resumeWindow() {
        fun r(p: Double, w: Boolean = false) = iptvResumePositionMs(IptvProgressEntry((dur * p).toLong(), dur, w))
        assertNull(r(0.02)); assertEquals((dur * 0.5).toLong(), r(0.5)); assertNull(r(0.91)); assertNull(r(0.5, true))
        assertNull(iptvResumePositionMs(null))
    }

    @Test fun capDropsOldest() {
        var map = m
        for (i in 0..2000) map = updateIptvProgress(map, "k$i", 1000, dur, false)
        assertEquals(2000, map.size); assertNull(map["k0"]); assertTrue("k2000" in map)
    }
}
