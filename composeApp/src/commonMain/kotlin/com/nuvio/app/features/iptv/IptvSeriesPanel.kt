package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage

/** "<Series> · S01E02 · <Episode title>"; the episode title is dropped when empty or equal to the series name. */
internal fun episodeTitle(seriesName: String, ep: IptvEpisode, index: Int): String {
    val code = "S${ep.season.toString().padStart(2, '0')}E${(ep.number ?: (index + 1)).toString().padStart(2, '0')}"
    val t = ep.title.trim().takeIf { it.isNotEmpty() && !it.equals(seriesName.trim(), ignoreCase = true) }
    return listOfNotNull(seriesName, code, t).joinToString(" · ")
}

private fun formatDuration(secs: Int?): String {
    val m = (secs ?: return "") / 60
    if (m <= 0) return ""
    return if (m >= 60) "${m / 60}:${(m % 60).toString().padStart(2, '0')}" else "${m}m"
}

/** Right-hand panel in Series mode (no video): cover, plot, season chips and the episode list. */
@Composable
internal fun IptvSeriesPanel(
    item: IptvItem?,
    series: IptvSeries?,
    info: IptvSeriesInfo?,
    error: String?,
    season: Int?,
    episodes: List<IptvEpisode>,
    episodeIndex: Int,
    episodeFocused: Boolean,
    episodeScroll: LazyListState,
    onSeason: (Int) -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onPlayEpisode: (Int) -> Unit,
    progress: Map<String, IptvProgressEntry>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(end = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (item == null) {
            Text("Select a series", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
            return@Column
        }
        val plot = series?.plot ?: info?.series?.plot
        val rating = (series?.rating ?: info?.series?.rating)?.takeIf { it != "0" }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NuvioAsyncImage(item.icon ?: info?.series?.cover, item.name, Modifier.height(180.dp).aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                rating?.let { Text("Rating $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) }
                plot?.let { Text(it, maxLines = 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        when {
            error != null -> Text(error, color = MaterialTheme.colorScheme.error)
            info == null -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
            episodes.isEmpty() -> Text("No episodes", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(info.seasons, key = { it }) { s -> FilterChip(s == season, { onSeason(s) }, { Text("S$s") }) }
                }
                LazyColumn(Modifier.fillMaxSize(), state = episodeScroll) {
                    items(episodes.size, key = { episodes[it].id }) { i ->
                        EpisodeRow(episodes[i], i, progress[iptvKeyFor(episodes[i].url)], i == episodeIndex && episodeFocused, { onSelectEpisode(i) }, { onPlayEpisode(i) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: IptvEpisode, index: Int, prog: IptvProgressEntry?, focused: Boolean, onSelect: () -> Unit, onOpen: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val select by rememberUpdatedState(onSelect)
    val open by rememberUpdatedState(onOpen)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (focused) MaterialTheme.colorScheme.surfaceContainerHigh else if (hovered) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background)
            .hoverable(hover).pointerInput(Unit) {
                // A click plays (the second tap of a double-click is swallowed by the screen's `opening` guard).
                detectTapGestures(onPress = { select() }, onTap = { open() })
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text((ep.number ?: (index + 1)).toString(), Modifier.width(28.dp), maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall)
        ep.image?.let { NuvioAsyncImage(it, null, Modifier.size(width = 64.dp, height = 36.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ep.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (prog != null && !prog.watched && prog.durationMs > 0L) {
                LinearProgressIndicator(progress = { (prog.positionMs.toFloat() / prog.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
        if (prog?.watched == true) Text("\u2713", color = MaterialTheme.colorScheme.primary)
        Text(formatDuration(ep.durationSecs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}
