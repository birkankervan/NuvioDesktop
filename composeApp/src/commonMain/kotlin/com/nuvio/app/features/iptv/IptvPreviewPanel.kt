package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.format.formatLocalTime
import com.nuvio.app.core.ui.NuvioAsyncImage
import kotlinx.coroutines.delay

/** Right-hand panel: live preview + now/next EPG for channels, poster + play for movies. */
@Composable
internal fun IptvPreviewPanel(
    item: IptvItem?,
    vod: Boolean,
    previewAllowed: Boolean,
    categoryName: String?,
    client: XtreamClient,
    gate: IptvPreviewGate,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(end = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (item == null) {
            Text("Select a channel", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
            return@Column
        }
        if (vod) {
            NuvioAsyncImage(item.icon, item.name, Modifier.height(340.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow), contentScale = ContentScale.Crop)
            Text(item.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            categoryName?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(onOpen) { Text("Oynat") }
            LaunchedEffect(item.id) {
                if (IptvUiState.vodInfos[item.id] == null) {
                    runCatching { client.vodInfo(item.id) }.onSuccess { IptvUiState.vodInfos[item.id] = it } // silent on error
                }
            }
            val info = IptvUiState.vodInfos[item.id]
            (info?.rating ?: item.rating)?.let {
                Text("★ $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            info?.plot?.let {
                Text(it, maxLines = 8, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        // Debounce so arrowing through the list does not open a provider connection per row.
        var previewUrl by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(item.url, previewAllowed) {
            if (!previewAllowed) { previewUrl = null; return@LaunchedEffect }
            if (previewUrl != item.url) {
                gate.selectedAt = kotlin.time.TimeSource.Monotonic.markNow()
                if (previewUrl != null) delay(200L) // first selection starts at once
                previewUrl = item.url
            }
        }
        SideEffect { gate.onDoubleClick = onOpen; gate.title = item.name }
        if (previewAllowed) {
            // Normal volume (the player's remembered level); there is no mute hook on the controller.
            IptvPreviewSurface(previewUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)), gate)
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerLow))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NuvioAsyncImage(item.icon, item.name, Modifier.size(48.dp), contentScale = ContentScale.Fit)
            Column(Modifier.weight(1f)) {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                categoryName?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Button(onOpen) { Text("Tam ekran") }
        }

        var epg by remember(item.id) { mutableStateOf<List<IptvEpgEntry>>(emptyList()) }
        LaunchedEffect(item.id) {
            delay(300L)
            epg = runCatching { client.shortEpg(item.id) }.getOrDefault(emptyList()) // silent when unavailable
        }
        epg.forEachIndexed { i, e ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    (if (i == 0) "Now  " else "Next  ") + "${formatLocalTime(e.startSec * 1000)} - ${formatLocalTime(e.endSec * 1000)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                if (e.description.isNotEmpty()) {
                    Text(e.description, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
