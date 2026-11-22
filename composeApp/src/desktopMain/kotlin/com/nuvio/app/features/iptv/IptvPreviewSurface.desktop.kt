package com.nuvio.app.features.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.LocalNuvioPlatformDensity
import com.nuvio.app.features.player.PlayerControlsState
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.app.features.player.desktop.NativePlayerEventSink
import com.nuvio.app.features.player.desktop.NativePlayerHandoff
import com.nuvio.app.features.player.desktop.AwtNativeViewResolver
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerHost
import kotlinx.coroutines.delay
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/** Window-relative bounds of the host canvas (points, top-left origin), kept current by AWT listeners. */
private class PreviewFrame {
    @Volatile var x = 0.0
    @Volatile var y = 0.0
    @Volatile var w = 0.0
    @Volatile var h = 0.0
}

@Composable
internal actual fun IptvPreviewSurface(url: String?, modifier: Modifier, gate: IptvPreviewGate) {
    val platformDensity = LocalNuvioPlatformDensity.current
    val host = remember { NativePlayerHost() }
    val frame = remember { PreviewFrame() }
    var hostViewPtr by remember { mutableStateOf(0L) }
    val isMac = DesktopHostOs.current == DesktopHostOs.MACOS
    var controlsVisible by remember { mutableStateOf(false) }
    val currentOnDoubleClick by rememberUpdatedState { gate.onDoubleClick() }
    // macOS: the resolved native view is the whole window, so the mpv view must be confined to the
    // host box (createPreview, no controls web view, no first-responder grab). Other OSes embed a
    // real child window and keep the shared create.
    val controller = remember(host) {
        NativePlayerController(host, acceptHandoff = false, nativeLoadUrl = if (isMac) NativePlayerBridge::loadPreviewUrl else null, nativeCreate = { hostView, source, headers, playWhenReady, positionMs, _, decoder, rtx, rtxHdr, sink ->
            NativePlayerBridge.ensureLoaded()
            if (isMac) {
                val previewSink = NativePlayerEventSink { type, value ->
                        when (type) {
                            "cursorActivity", "keepChromeVisible" -> controlsVisible = true
                            "hideChrome" -> controlsVisible = false
                            "toggleFullscreen" -> SwingUtilities.invokeLater { currentOnDoubleClick() }
                            "previewDoubleClick" -> SwingUtilities.invokeLater { currentOnDoubleClick() }
                            "previewFirstFrame" -> gate.selectedAt?.let { at ->
                                gate.selectedAt = null
                                val total = at.elapsedNow().inWholeMilliseconds
                                System.err.println("[IPTV-PREVIEW] select→loadfile ${total - value.toLong()}ms, loadfile→firstFrame ${value.toLong()}ms")
                            }
                            else -> sink.onPlayerEvent(type, value)
                        }
                    }
                // Returning from the full player: its core is waiting in the handoff slot; take it back (no reconnect).
                val returned = NativePlayerHandoff.take(source)?.let { taken ->
                    runCatching {
                        NativePlayerBridge.demoteToPreview(taken, hostView, previewSink, frame.x, frame.y, frame.w, frame.h, false)
                        NativePlayerBridge.setPaused(taken, false)
                    }.fold(
                        onSuccess = { taken },
                        onFailure = { runCatching { NativePlayerBridge.dispose(taken) }; null },
                    )
                }
                returned ?: NativePlayerBridge.createPreview(
                    hostView, source, headers, playWhenReady, positionMs, NativePlayerBridge.controlsPageUrl, decoder,
                    previewSink,
                    frame.x, frame.y, frame.w, frame.h,
                )
            } else {
                NativePlayerBridge.create(hostView, source, headers, playWhenReady, positionMs, "about:blank", decoder, rtx, rtxHdr, sink)
            }
        })
    }
    var firstPaint by remember { mutableStateOf(false) }
    var fullSizePaint by remember { mutableStateOf(false) }
    val decoderPriority = PlayerSettingsRepository.uiState.collectAsState().value.decoderPriority

    DisposableEffect(host) {
        host.onDisplayableChanged = { if (!it) { firstPaint = false; fullSizePaint = false } }
        host.onFirstPaint = { firstPaint = true }
        host.onFirstFullSizePaint = { fullSizePaint = true }
        // Track the host box (resize, window resize, scroll) and push it to the native container.
        fun push() {
            val window = SwingUtilities.getWindowAncestor(host) ?: return
            val p = SwingUtilities.convertPoint(host, 0, 0, window)
            frame.x = p.x.toDouble(); frame.y = p.y.toDouble(); frame.w = host.width.toDouble(); frame.h = host.height.toDouble()
            if (!isMac || !host.isDisplayable || host.width <= 1) return
            if (hostViewPtr == 0L) hostViewPtr = runCatching { AwtNativeViewResolver.resolveNativeViewPointer(host) }.getOrDefault(0L)
            if (hostViewPtr != 0L) runCatching {
                NativePlayerBridge.setPreviewFrame(hostViewPtr, frame.x, frame.y, frame.w, frame.h)
                if (host.isShowing) NativePlayerBridge.setPreviewHidden(hostViewPtr, false)
            }
        }
        val componentListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = push()
            override fun componentMoved(e: ComponentEvent) = push()
            override fun componentShown(e: ComponentEvent) = push()
        }
        val hierarchyListener = object : HierarchyBoundsAdapter() {
            override fun ancestorMoved(e: HierarchyEvent) = push()
            override fun ancestorResized(e: HierarchyEvent) = push()
        }
        // Tab switch (IPTV -> Home) hides the host without destroying it; hide the native container too.
        val showingListener = java.awt.event.HierarchyListener { e ->
            if ((e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L && isMac && hostViewPtr != 0L) {
                runCatching { NativePlayerBridge.setPreviewHidden(hostViewPtr, !host.isShowing) }
                if (host.isShowing) push()
            }
        }
        val mouseListener = object : MouseAdapter() { // heavyweight hosts that do receive AWT mouse events
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) currentOnDoubleClick() }
        }
        host.addComponentListener(componentListener)
        host.addHierarchyBoundsListener(hierarchyListener)
        host.addMouseListener(mouseListener)
        host.addHierarchyListener(showingListener)
        onDispose {
            host.removeHierarchyListener(showingListener)
            host.removeComponentListener(componentListener)
            host.removeHierarchyBoundsListener(hierarchyListener)
            host.removeMouseListener(mouseListener)
            host.onDisplayableChanged = null; host.onFirstPaint = null; host.onFirstFullSizePaint = null
        }
    }

    DisposableEffect(controller) {
        // After release the controller is unusable by design; the screen removes this composable.
        gate.handOff = { url -> isMac && controller.detachForHandoff(url) }
        gate.release = { done -> controller.releaseBeforeNavigation(onReleased = done, onReleaseFailed = { done() }) }
        onDispose {
            gate.release = null
            gate.handOff = null
            // Hide immediately even if native teardown is async.
            if (hostViewPtr != 0L) runCatching { NativePlayerBridge.setPreviewHidden(hostViewPtr, true) }
            controller.dispose()
        }
    }

    // One controller for the whole composition: attach() tears the previous stream down natively
    // before opening the next one, so at most one provider connection is open.
    LaunchedEffect(controller, url, fullSizePaint, decoderPriority) {
        if (url == null) { controller.dispose(); return@LaunchedEffect }
        if (!fullSizePaint) return@LaunchedEffect
        delay(16L)
        controller.attach(
            sourceUrl = url,
            sourceHeaders = emptyMap(),
            playWhenReady = true,
            initialPositionMs = 0L,
            decoderPriority = decoderPriority,
            nvidiaRtxSuperResolutionEnabled = false,
            onError = {},
        )
    }

    // HUD state; controller.updateControls stores it until the native player exists.
    LaunchedEffect(controller, gate.title, controlsVisible) {
        if (!isMac) return@LaunchedEffect
        controller.updateControls(
            PlayerControlsState(
                title = gate.title.orEmpty(),
                isPlaying = true,
                controlsVisible = controlsVisible,
                durationMs = 0L,
            ),
        )
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalDensity provides platformDensity) {
            SwingPanel(
                factory = { host },
                modifier = if (firstPaint) Modifier.fillMaxSize()
                else Modifier.align(Alignment.BottomEnd).requiredSize(1.dp),
                background = Color.Black,
            )
        }
    }
}
