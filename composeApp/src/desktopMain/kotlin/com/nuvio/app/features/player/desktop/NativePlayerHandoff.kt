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

    @Synchronized
    fun offer(url: String, handle: Long) {
        pending?.let { discard(it) }
        pending = Pending(url, handle, scheduler.schedule({ expire(handle) }, timeoutMs, TimeUnit.MILLISECONDS))
    }

    /** Returns the handle offered for exactly [url]; a pending handle for another url is disposed (one connection only). */
    @Synchronized
    fun take(url: String): Long? {
        val p = pending ?: return null
        pending = null
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
