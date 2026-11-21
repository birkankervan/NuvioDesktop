package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface IptvRow {
    val key: String
    data class Header(override val key: String, val label: String) : IptvRow
    data class Channel(val item: IptvItem) : IptvRow { override val key get() = item.id }
}

@Composable
internal fun IptvBrowser(account: IptvAccount, onPlay: (String, String) -> Unit) {
    val st = IptvUiState
    val client = remember(account) { st.client(account) }
    val mode = st.mode
    val series = mode == IptvMode.SERIES
    val categoryId = st.categoryIds[mode]
    val categories = st.categories[mode]
    val seriesRaw = if (series) categoryId?.let { st.seriesList[it] } else null
    // Series reuse the channel rows: a series is an IptvItem (id, name, cover) with no stream url.
    val rawItems = if (series) remember(seriesRaw) { seriesRaw?.map { IptvItem(it.id, it.name, it.cover, "", null, it.categoryId) } }
        else categoryId?.let { st.items[mode to it] }
    val selected = st.selected[mode]
    var error by remember(mode, categoryId) { mutableStateOf<String?>(null) }
    val loading = error == null && (categories == null || (categoryId != null && rawItems == null))
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var opening by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }

    // Back from the full player: give the server ~0.8s to release the old connection, then the preview may restart.
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        if (st.previewSuspended) { delay(800L); st.previewSuspended = false } // VOD path only
    }
    LaunchedEffect(mode) {
        if (st.categories[mode] != null) return@LaunchedEffect
        try {
            st.categories[mode] = if (series) client.seriesCategories() else client.categories(mode == IptvMode.VOD)
        } catch (e: IptvException) { error = e.message }
    }
    LaunchedEffect(mode, categoryId) {
        val id = categoryId ?: return@LaunchedEffect
        try {
            if (series) { if (st.seriesList[id] == null) st.seriesList[id] = client.series(id) }
            else if (st.items[mode to id] == null) st.items[mode to id] = client.items(mode == IptvMode.VOD, id)
        } catch (e: IptvException) { error = e.message }
    }

    // Series details: debounced like the preview, cached per series id for the process lifetime.
    val seriesSel = if (series) selected else null
    var infoError by remember(seriesSel?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(seriesSel?.id) {
        val id = seriesSel?.id ?: return@LaunchedEffect
        if (st.seriesInfos[id] != null) return@LaunchedEffect
        delay(200L)
        try { st.seriesInfos[id] = client.seriesInfo(id) } catch (e: IptvException) { infoError = e.message }
    }
    val info = seriesSel?.let { st.seriesInfos[it.id] }
    val season = seriesSel?.let { s -> st.seasonBySeries[s.id]?.takeIf { it in info?.seasons.orEmpty() } ?: info?.seasons?.firstOrNull() }
    val episodes = if (info != null && season != null) info.episodesBySeason[season].orEmpty() else emptyList()
    val epIndex = st.episodeIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
    val progress by IptvPlaybackProgress.progress.collectAsState()
    // Back from the player: if the episode just played is now watched, move the selection to the next one (no autoplay).
    LaunchedEffect(series, info, st.lastPlayedKey, progress) {
        val key = st.lastPlayedKey ?: return@LaunchedEffect
        val inf = info ?: return@LaunchedEffect
        if (progress[key]?.watched != true) return@LaunchedEffect
        val flat = inf.seasons.flatMap { s -> inf.episodesBySeason[s].orEmpty().mapIndexed { i, e -> Triple(s, i, e) } }
        val at = flat.indexOfFirst { iptvKeyFor(it.third.url) == key }
        if (at < 0) return@LaunchedEffect
        st.lastPlayedKey = null
        flat.getOrNull(at + 1)?.let { (s, i, _) -> seriesSel?.let { st.seasonBySeries[it.id] = s }; st.episodeIndex = i }
    }
    val epScroll = st.episodeScroll(seriesSel?.id.orEmpty(), season ?: 0)

    val visibleCategories = remember(categories, st.showAdult) {
        categories.orEmpty().let { c -> if (st.showAdult) c else c.filterNot { isAdultCategoryName(it.name) } }
    }
    val rows = remember(rawItems, st.showAdult, st.query) {
        val q = st.query.trim()
        buildList {
            rawItems.orEmpty().filter { st.showAdult || !isAdultItemName(it.name) }.forEach { item ->
                when {
                    isSeparatorName(item.name) -> if (q.isEmpty()) add(IptvRow.Header("h:${item.id}", separatorLabel(item.name)))
                    q.isEmpty() || item.name.contains(q, ignoreCase = true) -> add(IptvRow.Channel(item))
                }
            }
        }
    }
    val listState = categoryId?.let { st.itemScroll(mode, it) } ?: remember { LazyListState() }

    fun open(title: String, url: String) {
        if (opening) return
        opening = true
        // Full player; any preview must be fully released first (max_connections=1). Series has no preview mounted.
        val go = { st.previewSuspended = true; onPlay(title, url) }
        val release = st.previewGate.release
        if (release != null) release(go) else go()
    }

    fun play(item: IptvItem) {
        st.selected[mode] = item
        open(item.name, item.url)
    }

    fun playEpisode(i: Int) {
        val s = seriesSel ?: return
        val ep = episodes.getOrNull(i) ?: return
        st.episodeIndex = i; st.episodePane = true; st.lastPlayedKey = iptvKeyFor(ep.url)
        open(episodeTitle(s.name, ep, i), ep.url)
    }

    fun selectRow(item: IptvItem) { if (series) st.selectSeries(item) else st.selected[mode] = item }

    fun move(delta: Int) {
        val channels = rows.filterIsInstance<IptvRow.Channel>()
        if (channels.isEmpty()) return
        val cur = channels.indexOfFirst { it.item.id == selected?.id }
        val next = if (cur < 0) (if (delta > 0) 0 else channels.lastIndex) else (cur + delta).coerceIn(0, channels.lastIndex)
        val item = channels[next].item
        selectRow(item)
        val rowIndex = rows.indexOfFirst { it is IptvRow.Channel && it.item.id == item.id }
        scope.launch {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty() || rowIndex <= visible.first().index) listState.animateScrollToItem((rowIndex - 1).coerceAtLeast(0))
            else if (rowIndex >= visible.last().index) listState.animateScrollToItem((rowIndex - visible.size + 2).coerceAtLeast(0))
        }
    }

    fun moveEpisode(delta: Int) {
        if (episodes.isEmpty()) return
        val next = (epIndex + delta).coerceIn(0, episodes.lastIndex)
        st.episodeIndex = next
        scope.launch {
            val visible = epScroll.layoutInfo.visibleItemsInfo
            if (visible.isEmpty() || next <= visible.first().index) epScroll.animateScrollToItem((next - 1).coerceAtLeast(0))
            else if (next >= visible.last().index) epScroll.animateScrollToItem((next - visible.size + 2).coerceAtLeast(0))
        }
    }

    fun changeSeason(delta: Int) {
        val seasons = info?.seasons ?: return
        val next = seasons.getOrNull(seasons.indexOf(season) + delta) ?: return
        seriesSel?.let { st.seasonBySeries[it.id] = next }
        st.episodeIndex = 0
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val inEpisodes = series && st.episodePane && episodes.isNotEmpty()
                when (e.key) {
                    Key.DirectionDown -> { if (inEpisodes) moveEpisode(1) else move(1); true }
                    Key.DirectionUp -> { if (inEpisodes) moveEpisode(-1) else move(-1); true }
                    // Left/Right/brackets stay editable in the search field.
                    Key.DirectionRight -> if (series && !searchFocused && !st.episodePane && episodes.isNotEmpty()) { st.episodePane = true; true } else false
                    Key.DirectionLeft -> if (series && !searchFocused && st.episodePane) { st.episodePane = false; true } else false
                    Key.LeftBracket, Key.PageUp -> if (inEpisodes && !searchFocused) { changeSeason(-1); true } else false
                    Key.RightBracket, Key.PageDown -> if (inEpisodes && !searchFocused) { changeSeason(1); true } else false
                    Key.Enter, Key.NumPadEnter ->
                        if (inEpisodes) { playEpisode(epIndex); true }
                        else if (series) false // a series itself is not playable
                        else selected?.let { play(it); true } ?: false
                    else -> false
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == IptvMode.LIVE, { st.mode = IptvMode.LIVE; st.query = ""; st.episodePane = false }, { Text("Live") })
            FilterChip(mode == IptvMode.VOD, { st.mode = IptvMode.VOD; st.query = ""; st.episodePane = false }, { Text("Movies") })
            FilterChip(series, { st.mode = IptvMode.SERIES; st.query = ""; st.episodePane = false }, { Text("Series") })
            OutlinedTextField(st.query, { st.query = it }, Modifier.weight(1f).onFocusChanged { searchFocused = it.isFocused },
                placeholder = { Text("Search in category") }, singleLine = true)
            FilterChip(st.showAdult, { st.showAdult = !st.showAdult }, { Text("Show adult") })
            TextButton({ st.signOut() }) { Text("Remove account") }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.width(260.dp).fillMaxSize(), state = st.categoryScroll(mode)) {
                items(visibleCategories, key = { it.id }) { c ->
                    CategoryRow(c, c.id == categoryId, if (series) st.seriesList[c.id]?.size else st.items[mode to c.id]?.size) {
                        st.categoryIds[mode] = c.id; st.query = ""
                    }
                }
            }
            Box(Modifier.width(400.dp).fillMaxSize()) {
                when {
                    error != null -> Text(error.orEmpty(), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.error)
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    categoryId == null -> Text("Pick a category", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurface)
                    rows.isEmpty() -> Text("Nothing here", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurface)
                    else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(rows, key = { it.key }) { row ->
                            when (row) {
                                is IptvRow.Header -> SectionHeader(row.label)
                                is IptvRow.Channel -> ChannelRow(row.item, row.item.id == selected?.id,
                                    onSelect = { st.episodePane = false; selectRow(row.item) },
                                    onOpen = { if (!series) play(row.item) })
                            }
                        }
                    }
                }
            }
            if (series) {
                IptvSeriesPanel(
                    item = seriesSel, series = seriesSel?.let { s -> st.seriesList[s.categoryId ?: categoryId]?.firstOrNull { it.id == s.id } },
                    info = info, error = infoError, season = season, episodes = episodes,
                    episodeIndex = epIndex, episodeFocused = st.episodePane, episodeScroll = epScroll,
                    onSeason = { s -> seriesSel?.let { st.seasonBySeries[it.id] = s }; st.episodeIndex = 0 },
                    onSelectEpisode = { i -> st.episodeIndex = i; st.episodePane = true },
                    onPlayEpisode = ::playEpisode, progress = progress, modifier = Modifier.weight(1f).fillMaxSize(),
                )
            } else {
                IptvPreviewPanel(
                    item = selected, vod = mode == IptvMode.VOD, previewAllowed = !st.previewSuspended,
                    categoryName = categories?.firstOrNull { it.id == (selected?.categoryId ?: categoryId) }?.name,
                    client = client, gate = st.previewGate,
                    onOpen = { selected?.let(::play) }, modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(category: IptvCategory, selected: Boolean, count: Int?, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else if (hovered) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background)
            .hoverable(hover).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(category.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        // Counts are only known once a category has been opened (one request per category).
        count?.let { Text("$it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun SectionHeader(label: String) {
    if (label.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).size(width = 400.dp, height = 1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    } else {
        Text(label.uppercase(), Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, bottom = 4.dp), maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChannelRow(item: IptvItem, selected: Boolean, onSelect: () -> Unit, onOpen: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val select by rememberUpdatedState(onSelect)
    val open by rememberUpdatedState(onOpen)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else if (hovered) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background)
            .hoverable(hover).pointerInput(Unit) {
                // Select on press (onTap would wait for the double-tap timeout); 2nd tap opens.
                detectTapGestures(onPress = { select() }, onDoubleTap = { open() })
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(item.num?.toString().orEmpty(), Modifier.width(32.dp), maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
        NuvioAsyncImage(item.icon, item.name, Modifier.size(48.dp), contentScale = ContentScale.Fit)
        Text(item.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
