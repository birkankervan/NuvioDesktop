package com.nuvio.app.features.player.desktop

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One-slot handoff of a running native player (macOS IPTV preview -> full player) so the stream is not
 * reconnected. A handle nobody takes is disposed so the provider connection (often max 1) is freed.
 */
internal object NativePlayerHandoff {
    internal var timeoutMs = 5_000L
    internal var dispose: (Long) -> Unit = NativePlayerBridge::dispose

    private class Pending(val url: String, val handle: Long, val expiry: ScheduledFuture<*>)

    private var pending: Pending? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "nuvio-player-handoff").apply { isDaemon = true }
    }

    // dispose() hops to the main thread: decide under the monitor, dispose after leaving it.
    fun offer(url: String, handle: Long) {
        val old = synchronized(this) {
            val prev = pending
            pending = Pending(url, handle, scheduler.schedule({ expire(handle) }, timeoutMs, TimeUnit.MILLISECONDS))
            prev
        }
        old?.let { discard(it) }
    }

    /** Returns the handle offered for exactly [url]; a pending handle for another url is disposed (one connection only). */
    fun take(url: String): Long? {
        val p = synchronized(this) { pending.also { pending = null } } ?: return null
        p.expiry.cancel(false)
        if (p.url == url) return p.handle
        runCatching { dispose(p.handle) }
        return null
    }

    private fun discard(p: Pending) {
        p.expiry.cancel(false)
        runCatching { dispose(p.handle) }
    }

    private fun expire(handle: Long) {
        val p = synchronized(this) { pending?.takeIf { it.handle == handle }?.also { pending = null } } ?: return
        runCatching { dispose(p.handle) }
    }
}
