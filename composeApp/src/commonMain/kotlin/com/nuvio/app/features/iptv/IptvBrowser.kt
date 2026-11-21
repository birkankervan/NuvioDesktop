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
    val vod = st.vod
    val categoryId = st.categoryIds[vod]
    val categories = st.categories[vod]
    val rawItems = categoryId?.let { st.items[vod to it] }
    val selected = st.selected[vod]
    var error by remember(vod, categoryId) { mutableStateOf<String?>(null) }
    val loading = error == null && (categories == null || (categoryId != null && rawItems == null))
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var opening by remember { mutableStateOf(false) }

    // Back from the full player: give the server ~0.8s to release the old connection, then the preview may restart.
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        if (st.previewSuspended) { delay(800L); st.previewSuspended = false } // VOD path only
    }
    LaunchedEffect(vod) {
        if (st.categories[vod] != null) return@LaunchedEffect
        try { st.categories[vod] = client.categories(vod) } catch (e: IptvException) { error = e.message }
    }
    LaunchedEffect(vod, categoryId) {
        val id = categoryId ?: return@LaunchedEffect
        if (st.items[vod to id] != null) return@LaunchedEffect
        try { st.items[vod to id] = client.items(vod, id) } catch (e: IptvException) { error = e.message }
    }

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
    val listState = categoryId?.let { st.itemScroll(vod, it) } ?: remember { LazyListState() }

    fun play(item: IptvItem) {
        st.selected[vod] = item
        if (opening) return
        opening = true
        // Live and VOD: full player; any preview must be fully released first (max_connections=1).
        val open = { st.previewSuspended = true; onPlay(item.name, item.url) }
        val release = st.previewGate.release
        if (release != null) release(open) else open()
    }

    fun move(delta: Int) {
        val channels = rows.filterIsInstance<IptvRow.Channel>()
        if (channels.isEmpty()) return
        val cur = channels.indexOfFirst { it.item.id == selected?.id }
        val next = if (cur < 0) (if (delta > 0) 0 else channels.lastIndex) else (cur + delta).coerceIn(0, channels.lastIndex)
        val item = channels[next].item
        st.selected[vod] = item
        val rowIndex = rows.indexOfFirst { it is IptvRow.Channel && it.item.id == item.id }
        scope.launch {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty() || rowIndex <= visible.first().index) listState.animateScrollToItem((rowIndex - 1).coerceAtLeast(0))
            else if (rowIndex >= visible.last().index) listState.animateScrollToItem((rowIndex - visible.size + 2).coerceAtLeast(0))
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { move(1); true }
                    Key.DirectionUp -> { move(-1); true }
                    Key.Enter, Key.NumPadEnter -> selected?.let { play(it); true } ?: false
                    else -> false
                }
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!vod, { st.vod = false; st.query = "" }, { Text("Live") })
            FilterChip(vod, { st.vod = true; st.query = "" }, { Text("Movies") })
            OutlinedTextField(st.query, { st.query = it }, Modifier.weight(1f), placeholder = { Text("Search in category") }, singleLine = true)
            FilterChip(st.showAdult, { st.showAdult = !st.showAdult }, { Text("Show adult") })
            TextButton({ st.signOut() }) { Text("Remove account") }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.width(260.dp).fillMaxSize(), state = st.categoryScroll(vod)) {
                items(visibleCategories, key = { it.id }) { c ->
                    CategoryRow(c, c.id == categoryId, st.items[vod to c.id]?.size) {
                        st.categoryIds[vod] = c.id; st.query = ""
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
                                    onSelect = { st.selected[vod] = row.item }, onOpen = { play(row.item) })
                            }
                        }
                    }
                }
            }
            IptvPreviewPanel(
                item = selected, vod = vod, previewAllowed = !st.previewSuspended,
                categoryName = categories?.firstOrNull { it.id == (selected?.categoryId ?: categoryId) }?.name,
                client = client, gate = st.previewGate,
                onOpen = { selected?.let(::play) }, modifier = Modifier.weight(1f).fillMaxSize(),
            )
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
