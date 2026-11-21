package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// ponytail: desktop-only feature; other targets get an empty placeholder.
@Composable
internal actual fun IptvPreviewSurface(url: String?, modifier: Modifier, gate: IptvPreviewGate) {
    Box(modifier.background(Color.Black))
}
