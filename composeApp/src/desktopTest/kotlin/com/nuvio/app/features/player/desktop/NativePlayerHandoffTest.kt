package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativePlayerHandoffTest {
    private fun <T> withSlot(timeout: Long = 5_000L, block: (disposed: MutableList<Long>) -> T): T {
        val disposed = mutableListOf<Long>()
        val oldDispose = NativePlayerHandoff.dispose
        val oldTimeout = NativePlayerHandoff.timeoutMs
        NativePlayerHandoff.dispose = { synchronized(disposed) { disposed += it } }
        NativePlayerHandoff.timeoutMs = timeout
        try { return block(disposed) } finally {
            NativePlayerHandoff.take("")
            NativePlayerHandoff.dispose = oldDispose
            NativePlayerHandoff.timeoutMs = oldTimeout
        }
    }

    @Test
    fun takeMatchesExactUrlOnly() = withSlot { disposed ->
        NativePlayerHandoff.offer("a", 1L)
        assertEquals(1L, NativePlayerHandoff.take("a"))
        assertNull(NativePlayerHandoff.take("a"))
        NativePlayerHandoff.offer("a", 2L)
        assertNull(NativePlayerHandoff.take("b"))
        assertEquals(listOf(2L), disposed)
    }

    @Test
    fun newOfferDisposesPrevious() = withSlot { disposed ->
        NativePlayerHandoff.offer("a", 1L)
        NativePlayerHandoff.offer("b", 2L)
        assertEquals(listOf(1L), disposed)
        assertEquals(2L, NativePlayerHandoff.take("b"))
    }

    @Test
    fun untakenHandleIsDisposedAfterTimeout() = withSlot(timeout = 50L) { disposed ->
        NativePlayerHandoff.offer("a", 7L)
        Thread.sleep(400)
        assertEquals(listOf(7L), synchronized(disposed) { disposed.toList() })
        assertNull(NativePlayerHandoff.take("a"))
    }
}
