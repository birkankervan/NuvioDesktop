package com.nuvio.app.features.iptv

import com.nuvio.app.features.watching.domain.isProgressComplete
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Serializable
internal data class IptvProgressEntry(val positionMs: Long, val durationMs: Long, val watched: Boolean)

private const val MAX_ENTRIES = 2000
private const val RESUME_MIN = 0.03
private const val RESUME_MAX = 0.90

/** Start position for an unwatched entry between 3% and 90%, else null. */
internal fun iptvResumePositionMs(e: IptvProgressEntry?): Long? {
    if (e == null || e.watched || e.durationMs <= 0L) return null
    val f = e.positionMs.toDouble() / e.durationMs
    return if (f >= RESUME_MIN && f < RESUME_MAX) e.positionMs else null
}

/** Pure update step: ignores live (duration <= 0), re-inserts the key as newest, caps size. */
internal fun updateIptvProgress(
    map: Map<String, IptvProgressEntry>,
    key: String,
    positionMs: Long,
    durationMs: Long,
    ended: Boolean,
): Map<String, IptvProgressEntry> {
    if (durationMs <= 0L) return map
    val watched = isProgressComplete(positionMs, durationMs, ended)
    val next = LinkedHashMap(map)
    next.remove(key)
    next[key] = IptvProgressEntry(positionMs, durationMs, watched)
    while (next.size > MAX_ENTRIES) next.remove(next.keys.first())
    return next
}

/** Process-wide IPTV watch progress, keyed by [iptvKeyFor]; persisted as key -> numbers only. */
internal object IptvPlaybackProgress {
    private val _progress = MutableStateFlow(load())
    val progress: StateFlow<Map<String, IptvProgressEntry>> = _progress

    // Ticks update [latest] only; the StateFlow (collected by the browser) is published on save/flush/watched.
    private var latest = _progress.value
    private var lastSave = TimeSource.Monotonic.markNow()
    private var dirty = false

    fun report(key: String, positionMs: Long, durationMs: Long, ended: Boolean) {
        val next = updateIptvProgress(latest, key, positionMs, durationMs, ended)
        if (next === latest) return
        latest = next
        dirty = true
        if (next[key]?.watched == true || lastSave.elapsedNow() >= 10.seconds) flush()
    }

    fun flush() {
        if (latest !== _progress.value) _progress.value = latest
        if (!dirty) return
        dirty = false
        lastSave = TimeSource.Monotonic.markNow()
        runCatching { IptvStorage.saveProgress(Json.encodeToString(latest)) }
    }

    private fun load(): Map<String, IptvProgressEntry> =
        runCatching { Json.decodeFromString<Map<String, IptvProgressEntry>>(IptvStorage.loadProgress().orEmpty()) }
            .getOrDefault(emptyMap())
}
