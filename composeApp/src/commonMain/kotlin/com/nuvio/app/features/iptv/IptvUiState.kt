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

internal enum class IptvMode { LIVE, VOD, SERIES }

/**
 * Process-lifetime IPTV UI state. Navigating to the player disposes the whole tab composition
 * (NavDisplay shows only the top entry), so anything kept in `remember` was lost on Back.
 * Maps keyed by [IptvMode] hold per-tab state.
 */
internal object IptvUiState {
    var account by mutableStateOf(loadAccount())
    var mode by mutableStateOf(IptvMode.LIVE)
    var showAdult by mutableStateOf(false)
    var query by mutableStateOf("")

    /** True while the full player is (about to be) showing; the preview must stay stopped. */
    var previewSuspended by mutableStateOf(false)
    val previewGate = IptvPreviewGate()

    val categoryIds = mutableStateMapOf<IptvMode, String>()
    /** Selected row per tab; in SERIES it is the series (id, name, cover) as an [IptvItem]. */
    val selected = mutableStateMapOf<IptvMode, IptvItem>()
    val categories = mutableStateMapOf<IptvMode, List<IptvCategory>>()
    /** LIVE and VOD streams only; series live in [seriesList]. */
    val items = mutableStateMapOf<Pair<IptvMode, String>, List<IptvItem>>()

    // Series: list per category id, seriesInfo per series id (process-lifetime caches).
    val seriesList = mutableStateMapOf<String, List<IptvSeries>>()
    val seriesInfos = mutableStateMapOf<String, IptvSeriesInfo>()
    val seasonBySeries = mutableStateMapOf<String, Int>()
    var episodeIndex by mutableStateOf(0)
    /** True while keyboard focus is in the episode list instead of the series list. */
    var episodePane by mutableStateOf(false)

    fun selectSeries(item: IptvItem) {
        if (selected[IptvMode.SERIES]?.id == item.id) return
        selected[IptvMode.SERIES] = item; episodeIndex = 0; episodePane = false
    }

    private val categoryScroll = HashMap<IptvMode, LazyListState>()
    private val itemScroll = HashMap<Pair<IptvMode, String>, LazyListState>()
    private val episodeScroll = HashMap<Pair<String, Int>, LazyListState>()
    fun categoryScroll(mode: IptvMode) = categoryScroll.getOrPut(mode) { LazyListState() }
    fun itemScroll(mode: IptvMode, categoryId: String) = itemScroll.getOrPut(mode to categoryId) { LazyListState() }
    fun episodeScroll(seriesId: String, season: Int) = episodeScroll.getOrPut(seriesId to season) { LazyListState() }

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
        account = null; query = ""; previewSuspended = false; mode = IptvMode.LIVE
        categoryIds.clear(); selected.clear(); categories.clear(); items.clear()
        seriesList.clear(); seriesInfos.clear(); seasonBySeries.clear(); episodeIndex = 0; episodePane = false
        categoryScroll.clear(); itemScroll.clear(); episodeScroll.clear()
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
