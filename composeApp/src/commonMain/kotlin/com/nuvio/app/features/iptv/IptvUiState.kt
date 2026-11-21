package com.nuvio.app.features.iptv

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json

private fun loadAccount(): IptvAccount? =
    IptvStorage.load()?.let { runCatching { Json.decodeFromString<IptvAccount>(it) }.getOrNull() }
        ?.let { it.copy(server = it.server.trim(), username = it.username.trim(), password = it.password.trim()) }

/**
 * Process-lifetime IPTV UI state. Navigating to the player disposes the whole tab composition
 * (NavDisplay shows only the top entry), so anything kept in `remember` was lost on Back.
 * Keys with a Boolean are `vod`.
 */
internal object IptvUiState {
    var account by mutableStateOf(loadAccount())
    var vod by mutableStateOf(false)
    var showAdult by mutableStateOf(false)
    var query by mutableStateOf("")

    /** True while the full player is (about to be) showing; the preview must stay stopped. */
    var previewSuspended by mutableStateOf(false)
    val previewGate = IptvPreviewGate()

    val categoryIds = mutableStateMapOf<Boolean, String>()
    val selected = mutableStateMapOf<Boolean, IptvItem>()
    val categories = mutableStateMapOf<Boolean, List<IptvCategory>>()
    val items = mutableStateMapOf<Pair<Boolean, String>, List<IptvItem>>()

    private val categoryScroll = HashMap<Boolean, LazyListState>()
    private val itemScroll = HashMap<Pair<Boolean, String>, LazyListState>()
    fun categoryScroll(vod: Boolean) = categoryScroll.getOrPut(vod) { LazyListState() }
    fun itemScroll(vod: Boolean, categoryId: String) = itemScroll.getOrPut(vod to categoryId) { LazyListState() }

    private var client: XtreamClient? = null
    private var clientAccount: IptvAccount? = null
    fun client(account: IptvAccount): XtreamClient {
        if (clientAccount != account) {
            client?.close()
            client = XtreamClient(account).also { clientAccount = account }
        }
        return client!!
    }

    fun signOut() {
        IptvStorage.save(null)
        client?.close(); client = null; clientAccount = null
        account = null; query = ""; previewSuspended = false
        categoryIds.clear(); selected.clear(); categories.clear(); items.clear()
        categoryScroll.clear(); itemScroll.clear()
    }
}

/** Provider "separators" are fake channels like "▃▅▆█ SPOR █▆▅▃": mostly block/box chars or no letters at all. */
internal fun isSeparatorName(name: String): Boolean {
    val visible = name.filterNot { it.isWhitespace() }
    if (visible.isEmpty()) return true
    if (visible.none { it.isLetterOrDigit() }) return true
    val deco = visible.count { it in '─'..'▟' }
    return deco >= 3 && deco * 2 >= visible.length
}

/** Header text for a separator, or empty for a plain divider. */
internal fun separatorLabel(name: String): String =
    name.filterNot { it in '─'..'▟' }.trim { !it.isLetterOrDigit() }
