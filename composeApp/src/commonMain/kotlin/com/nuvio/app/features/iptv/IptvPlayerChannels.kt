package com.nuvio.app.features.iptv

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState

/** Channels of the category a live channel was launched from; feeds the player's Sources panel as "Channels". Empty for VOD/episodes. */
internal object IptvPlayerChannels {
    var channels: List<IptvItem> = emptyList()

    /** Live launches are the only ones with channels; their HLS reports a ~30s timeshift duration, so no progress/resume. */
    val isLive get() = channels.isNotEmpty()

    fun asStreamsState(): StreamsUiState = StreamsUiState(
        groups = listOf(
            AddonStreamGroup(
                addonName = "IPTV",
                addonId = "iptv",
                streams = channels.map { StreamItem(name = it.name, url = it.url, addonName = "IPTV", addonId = "iptv", addonLogo = it.icon) },
            ),
        ),
    )

    /** Keeps the browser selection on the playing channel so Back can hand the running core to the preview. */
    fun onSwitched(url: String?) {
        channels.firstOrNull { it.url == url }?.let { IptvUiState.selected[IptvMode.LIVE] = it }
    }
}
