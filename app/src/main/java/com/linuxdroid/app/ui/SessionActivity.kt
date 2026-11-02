package com.linuxdroid.app.ui

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.linuxdroid.app.input.ImeBridge
import com.linuxdroid.app.input.ImeCaptureView
import com.linuxdroid.app.input.TrackpadController
import com.linuxdroid.app.input.TwoFingerGestures
import com.linuxdroid.app.runtime.LinuxRuntime
import com.linuxdroid.app.session.ResolutionPreset
import com.linuxdroid.app.session.SessionPhase
import com.linuxdroid.app.session.SessionPrefs
import com.linuxdroid.app.session.SessionService
import com.linuxdroid.app.session.SessionState
import com.linuxdroid.app.session.TouchInputMode
import com.linuxdroid.app.wayland.CompositorHost
import com.linuxdroid.app.wayland.DeXEnvironmentDetector
import com.linuxdroid.app.wayland.DisplayRoutingMode
import com.linuxdroid.app.wayland.InputSpace
import com.linuxdroid.app.wayland.KeyCodes
import com.linuxdroid.app.wayland.WaylandCompositor
import java.io.File

class SessionActivity : ComponentActivity() {
    private lateinit var desktopSurfaceView: SurfaceView
    private lateinit var imeCaptureView: ImeCaptureView
    private lateinit var imeBridge: ImeBridge

    private val touchGestures = TwoFingerGestures()
    private val trackpadController = TrackpadController()
    private var prevMouseButtonMask = 0

    private val showQuickMenuState = mutableStateOf(false)
    private val externalMonitorActiveState = mutableStateOf(false)
    private val touchModeState = mutableStateOf(TouchInputMode.TOUCHSCREEN)

    private var externalPresentation: ExternalMonitorPresentation? = null
    private var displayManager: DisplayManager? = null
    private var vsyncRunning = false

    private val vsyncCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!vsyncRunning) return
            WaylandCompositor.nativeVsync(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshDisplayRouting()
        override fun onDisplayRemoved(displayId: Int) = refreshDisplayRouting()
        override fun onDisplayChanged(displayId: Int) = refreshDisplayRouting()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!LinuxRuntime.isReady(this)) {
            startActivity(Intent(this, FrontEndActivity::class.java).putExtra(FrontEndActivity.EXTRA_SKIP_AUTOBOOT, true))
            finish()
            return
        }

        touchModeState.value = SessionPrefs.touchInputMode(this)
        applyWindowAndDisplayMode()

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            keepScreenOn = true
        }

        desktopSurfaceView = SurfaceView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (!externalMonitorActiveState.value) {
                        bindInternalSurface(holder)
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (!externalMonitorActiveState.value && width > 0 && height > 0) {
                        val (outW, outH) = computeDesktopOutputSize(width, height)
                        val hz = selectBestRefreshRate()
                        CompositorHost.attachSurface(holder.surface, outW, outH, hz)
                        ensureSessionStarted(outW, outH, hz)
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    if (!externalMonitorActiveState.value) {
                        CompositorHost.detachSurface()
                    }
                }
            })
            setOnTouchListener { v, event -> handleMotionInput(v, event) }
            setOnGenericMotionListener { v, event -> handleGenericPointer(v, event) }
        }

        imeCaptureView = ImeCaptureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
        }
        imeBridge = ImeBridge(
            context = this,
            captureView = imeCaptureView,
            imeStateFile = File(LinuxRuntime.sessionDir(this), "ime.json"),
        )

        val overlayCompose = ComposeView(this).apply {
            setContent {
                LinuxDroidTheme {
                    SessionOverlayUi(
                        externalMonitorActive = externalMonitorActiveState.value,
                        showQuickMenu = showQuickMenuState.value,
                        touchMode = touchModeState.value,
                        onDismissQuickMenu = {
                            showQuickMenuState.value = false
                            hideSystemBarsIfFullscreen()
                        },
                        onOpenQuickMenu = { showQuickMenuState.value = true },
                        onToggleKeyboard = {
                            showQuickMenuState.value = false
                            imeBridge.toggleKeyboard()
                        },
                        onToggleTouchMode = {
                            val next = if (touchModeState.value == TouchInputMode.TOUCHSCREEN) {
                                TouchInputMode.TRACKPAD
                            } else {
                                TouchInputMode.TOUCHSCREEN
                            }
                            touchModeState.value = next
                            SessionPrefs.setTouchInputMode(this@SessionActivity, next)
                            showQuickMenuState.value = false
                        },
                        onCycleOrientation = {
                            val next = SessionPrefs.orientation(this@SessionActivity).next()
                            SessionPrefs.setOrientation(this@SessionActivity, next)
                            requestedOrientation = next.androidOrientation
                            showQuickMenuState.value = false
                        },
                        onOpenTerminal = {
                            showQuickMenuState.value = false
                            startActivity(Intent(this@SessionActivity, TerminalActivity::class.java))
                        },
                        onTogglePause = {
                            showQuickMenuState.value = false
                            SessionService.togglePause(this@SessionActivity)
                        },
                        onOpenDashboard = {
                            showQuickMenuState.value = false
                            startActivity(
                                Intent(this@SessionActivity, FrontEndActivity::class.java)
                                    .putExtra(FrontEndActivity.EXTRA_SKIP_AUTOBOOT, true)
                            )
                        },
                        onShutdown = {
                            showQuickMenuState.value = false
                            SessionService.shutdown(this@SessionActivity)
                            finish()
                        },
                    )
                }
            }
        }

        rootFrame.addView(
            desktopSurfaceView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        rootFrame.addView(imeCaptureView)
        rootFrame.addView(
            overlayCompose,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(rootFrame)

        displayManager = getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(displayListener, null)
    }

    override fun onStart() {
        super.onStart()
        imeBridge.start()
        vsyncRunning = true
        Choreographer.getInstance().postFrameCallback(vsyncCallback)
        refreshDisplayRouting()
    }

    override fun onResume() {
        super.onResume()
        applyWindowAndDisplayMode()
        imeCaptureView.requestFocus()
    }

    override fun onStop() {
        vsyncRunning = false
        Choreographer.getInstance().removeFrameCallback(vsyncCallback)
        imeBridge.stop()
        dismissExternalPresentation()
        super.onStop()
    }

    override fun onDestroy() {
        displayManager?.unregisterDisplayListener(displayListener)
        dismissExternalPresentation()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWindowAndDisplayMode()
        refreshDisplayRouting()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val kc = event.keyCode
        // Volume keys remain native Android volume controls
        if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN || kc == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event)
        }

        // Ctrl + Alt + Shift + Esc opens the LinuxDroid Quick Menu from hardware keyboards
        if (kc == KeyEvent.KEYCODE_ESCAPE &&
            event.isCtrlPressed &&
            event.isAltPressed &&
            event.isShiftPressed
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                showQuickMenuState.value = !showQuickMenuState.value
            }
            return true
        }

        // System Back button/gesture opens Quick Menu (while hardware Escape goes 100% straight to Linux!)
        if (kc == KeyEvent.KEYCODE_BACK && (event.source and InputDevice.SOURCE_MOUSE) == 0) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                showQuickMenuState.value = !showQuickMenuState.value
            }
            return true
        }

        val evdev = KeyCodes.fromKeyCode(kc)
        if (evdev >= 0) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) WaylandCompositor.nativeSendKey(evdev, 1)
                KeyEvent.ACTION_UP -> WaylandCompositor.nativeSendKey(evdev, 0)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleMotionInput(view: View, event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            return handleMouseTouchEvent(view, event)
        }
        // When external monitor hot-swap is active, phone screen automatically acts as a multi-touch trackpad
        if (externalMonitorActiveState.value || touchModeState.value == TouchInputMode.TRACKPAD) {
            return trackpadController.onTouchEvent(event)
        }
        return touchGestures.onTouchEvent(event, view.width, view.height)
    }

    private fun handleMouseTouchEvent(view: View, event: MotionEvent): Boolean {
        val ix = InputSpace.x(event.x, view.width)
        val iy = InputSpace.y(event.y, view.height)
        WaylandCompositor.nativeSendPointer(InputSpace.POINTER_HOVER, ix, iy)
        syncMouseButtons(event.buttonState)
        if (event.actionMasked == MotionEvent.ACTION_UP && event.buttonState == 0) {
            syncMouseButtons(0)
        }
        return true
    }

    private fun handleGenericPointer(view: View, event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return false
        val ix = InputSpace.x(event.x, view.width)
        val iy = InputSpace.y(event.y, view.height)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                WaylandCompositor.nativeSendPointer(InputSpace.POINTER_HOVER, ix, iy)
                syncMouseButtons(event.buttonState)
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val vert120 = (-vScroll * 120f).toInt()
                val horiz120 = (hScroll * 120f).toInt()
                if (vert120 != 0 || horiz120 != 0) {
                    WaylandCompositor.sendWheelScroll(vert120, horiz120)
                }
                return true
            }
        }
        return false
    }

    private fun syncMouseButtons(buttonState: Int) {
        val changed = buttonState xor prevMouseButtonMask
        if (changed == 0) return
        prevMouseButtonMask = buttonState
        val map = listOf(
            MotionEvent.BUTTON_PRIMARY to InputSpace.BTN_LEFT,
            MotionEvent.BUTTON_SECONDARY to InputSpace.BTN_RIGHT,
            MotionEvent.BUTTON_TERTIARY to InputSpace.BTN_MIDDLE,
            MotionEvent.BUTTON_BACK to InputSpace.BTN_SIDE,
            MotionEvent.BUTTON_FORWARD to InputSpace.BTN_EXTRA,
        )
        for ((androidMask, evdevBtn) in map) {
            if ((changed and androidMask) != 0) {
                WaylandCompositor.sendButton(evdevBtn, (buttonState and androidMask) != 0)
            }
        }
    }

    private fun bindInternalSurface(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        val w = frame.width().takeIf { it > 0 } ?: desktopSurfaceView.width.takeIf { it > 0 } ?: 1920
        val h = frame.height().takeIf { it > 0 } ?: desktopSurfaceView.height.takeIf { it > 0 } ?: 1080
        val (outW, outH) = computeDesktopOutputSize(w, h)
        val hz = selectBestRefreshRate()
        CompositorHost.ensureStarted(this, LinuxRuntime.runtimeDir(this), outW, outH, hz, holder.surface)
        CompositorHost.attachSurface(holder.surface, outW, outH, hz)
        ensureSessionStarted(outW, outH, hz)
    }

    private fun refreshDisplayRouting() {
        val (mode, extDisplay) = DeXEnvironmentDetector.detectForActivity(this)
        if (mode == DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP && extDisplay != null) {
            if (externalPresentation?.display?.displayId != extDisplay.displayId) {
                dismissExternalPresentation()
                externalMonitorActiveState.value = true
                externalPresentation = ExternalMonitorPresentation(this, extDisplay).also {
                    runCatching { it.show() }.onFailure {
                        externalMonitorActiveState.value = false
                        externalPresentation = null
                    }
                }
            }
        } else {
            if (externalPresentation != null || externalMonitorActiveState.value) {
                dismissExternalPresentation()
                val holder = desktopSurfaceView.holder
                if (holder.surface != null && holder.surface.isValid) {
                    bindInternalSurface(holder)
                }
            }
        }
    }

    private fun dismissExternalPresentation() {
        externalMonitorActiveState.value = false
        runCatching { externalPresentation?.dismiss() }
        externalPresentation = null
    }

    private fun computeDesktopOutputSize(surfaceW: Int, surfaceH: Int): Pair<Int, Int> {
        val (routing, _) = DeXEnvironmentDetector.detectForActivity(this)
        if (routing == DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW) {
            return (surfaceW and 1.inv()).coerceAtLeast(640) to (surfaceH and 1.inv()).coerceAtLeast(480)
        }
        val preset = SessionPrefs.resolutionPreset(this)
        if (preset == ResolutionPreset.NATIVE) {
            return (surfaceW and 1.inv()).coerceAtLeast(640) to (surfaceH and 1.inv()).coerceAtLeast(480)
        }
        return if (surfaceW >= surfaceH) {
            preset.width to preset.height
        } else {
            preset.height to preset.width
        }
    }

    private fun selectBestRefreshRate(displayOverride: Display? = null): Float {
        val wantHz = SessionPrefs.targetRefreshHz(this)
        @Suppress("DEPRECATION")
        val disp = displayOverride ?: windowManager.defaultDisplay ?: return 60f
        val modes = disp.supportedModes
        val chosen = if (wantHz > 0) {
            modes.minByOrNull { kotlin.math.abs(it.refreshRate - wantHz) }
        } else {
            modes.maxByOrNull { it.refreshRate }
        }
        if (displayOverride == null && chosen != null) {
            val attrs = window.attributes
            if (attrs.preferredDisplayModeId != chosen.modeId) {
                attrs.preferredDisplayModeId = chosen.modeId
                window.attributes = attrs
            }
        }
        return (chosen?.refreshRate ?: disp.refreshRate).coerceIn(30f, 240f)
    }

    private fun ensureSessionStarted(width: Int, height: Int, refreshHz: Float) {
        if (!SessionState.isRunningOrPaused) {
            SessionService.startOrResume(this, width, height, refreshHz)
        } else if (SessionState.isPaused) {
            SessionService.startOrResume(this, width, height, refreshHz)
        }
    }

    private fun applyWindowAndDisplayMode() {
        val (routing, _) = DeXEnvironmentDetector.detectForActivity(this)
        requestedOrientation = if (routing == DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            SessionPrefs.orientation(this).androidOrientation
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (routing != DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW) {
            hideSystemBarsIfFullscreen()
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }

    private fun hideSystemBarsIfFullscreen() {
        val (routing, _) = DeXEnvironmentDetector.detectForActivity(this)
        if (routing == DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private inner class ExternalMonitorPresentation(
        outerContext: Context,
        display: Display,
    ) : Presentation(outerContext, display) {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val extSurfaceView = SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val mode = display.mode
                        val w = (mode.physicalWidth and 1.inv()).coerceAtLeast(1280)
                        val h = (mode.physicalHeight and 1.inv()).coerceAtLeast(720)
                        val hz = selectBestRefreshRate(display)
                        CompositorHost.ensureStarted(this@SessionActivity, LinuxRuntime.runtimeDir(this@SessionActivity), w, h, hz, holder.surface)
                        CompositorHost.attachSurface(holder.surface, w, h, hz)
                        ensureSessionStarted(w, h, hz)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        if (width > 0 && height > 0) {
                            val hz = selectBestRefreshRate(display)
                            CompositorHost.attachSurface(holder.surface, width and 1.inv(), height and 1.inv(), hz)
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                })
            }
            setContentView(extSurfaceView)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionOverlayUi(
    externalMonitorActive: Boolean,
    showQuickMenu: Boolean,
    touchMode: TouchInputMode,
    onDismissQuickMenu: () -> Unit,
    onOpenQuickMenu: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleTouchMode: () -> Unit,
    onCycleOrientation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onTogglePause: () -> Unit,
    onOpenDashboard: () -> Unit,
    onShutdown: () -> Unit,
) {
    val snap by SessionState.state.collectAsState()

    // Trackpad UI shown ONLY on the phone screen while outputting desktop fullscreen to an external USB-C/HDMI monitor
    if (externalMonitorActive) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1117))
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Mouse,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "External Monitor Active • Multi-Touch Trackpad",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "1-finger move/tap • 2-finger scroll & right-click • 3-finger middle-click",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                OutlinedButton(onClick = onToggleKeyboard) {
                    Icon(Icons.Outlined.Keyboard, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Keyboard")
                }
                OutlinedButton(onClick = onOpenQuickMenu) {
                    Text("Quick Menu")
                }
            }
        }
    } else if (!snap.firstFrameReady && (snap.phase == SessionPhase.STARTING || snap.phase == SessionPhase.RUNNING)) {
        // Minimal boot indicator before first Wayland frame arrives; disappears completely once desktop renders
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1117)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("Starting Linux Desktop...", style = MaterialTheme.typography.titleMedium)
                if (snap.activeGpuSummary.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = snap.activeGpuSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showQuickMenu) {
        ModalBottomSheet(
            onDismissRequest = onDismissQuickMenu,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "LinuxDroid Quick Menu",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${snap.statusText} • ${snap.activeGpuSummary.ifEmpty { "ARM64 Workstation" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                QuickMenuAction(Icons.Outlined.Keyboard, "Toggle Android Keyboard", onToggleKeyboard)
                QuickMenuAction(
                    if (touchMode == TouchInputMode.TOUCHSCREEN) Icons.Outlined.Mouse else Icons.Outlined.TouchApp,
                    if (touchMode == TouchInputMode.TOUCHSCREEN) "Switch to Virtual Trackpad" else "Switch to Direct Touchscreen",
                    onToggleTouchMode,
                )
                QuickMenuAction(Icons.Outlined.ScreenRotation, "Cycle Screen Orientation (Landscape / Auto / Portrait)", onCycleOrientation)
                QuickMenuAction(Icons.Outlined.Terminal, "Open Linux Terminal", onOpenTerminal)
                QuickMenuAction(
                    Icons.Outlined.Pause,
                    if (snap.phase == SessionPhase.PAUSED) "Resume Frozen Session" else "Pause / Freeze Session (0% CPU)",
                    onTogglePause,
                )
                QuickMenuAction(Icons.Outlined.Dashboard, "Workstation Dashboard & Settings", onOpenDashboard)

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Button(
                    onClick = onShutdown,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Shut Down Linux Workstation")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun QuickMenuAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
