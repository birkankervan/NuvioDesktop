package com.nuvio.app.features.iptv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Lets the screen stop the embedded preview and wait until the server connection is released
 * (providers often allow max_connections=1) before the full player opens.
 */
internal class IptvPreviewGate {
    /** When the current channel was selected; used only for the [IPTV-PREVIEW] timing log. */
    var selectedAt: kotlin.time.TimeMark? = null

    var release: ((onDone: () -> Unit) -> Unit)? = null

    /** Double-click on the preview video (delivered by the native view, which swallows mouse events). */
    var onDoubleClick: () -> Unit = {}
}

/** Embedded live preview. Desktop plays [url] via mpv; other platforms draw a placeholder. */
@Composable
internal expect fun IptvPreviewSurface(url: String?, modifier: Modifier, gate: IptvPreviewGate)
