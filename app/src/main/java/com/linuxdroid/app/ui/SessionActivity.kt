package com.linuxdroid.app.ui

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.linuxdroid.app.gpu.GpuDriverMode
import com.linuxdroid.app.input.ImeBridge
import com.linuxdroid.app.input.ImeCaptureView
import com.linuxdroid.app.input.TrackpadController
import com.linuxdroid.app.input.TwoFingerGestures
import com.linuxdroid.app.runtime.LinuxRuntime
import com.linuxdroid.app.session.ExternalDisplayMode
import com.linuxdroid.app.session.SessionPhase
import com.linuxdroid.app.session.SessionPrefs
import com.linuxdroid.app.session.SessionService
import com.linuxdroid.app.session.SessionSnapshot
import com.linuxdroid.app.session.SessionState
import com.linuxdroid.app.session.TouchInputMode
import com.linuxdroid.app.wayland.CompositorHost
import com.linuxdroid.app.wayland.DeXEnvironmentDetector
import com.linuxdroid.app.wayland.DisplayRoutingMode
import com.linuxdroid.app.wayland.InputSpace
import com.linuxdroid.app.wayland.KeyCodes
import com.linuxdroid.app.wayland.WaylandCompositor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class SessionActivity : ComponentActivity() {
    private lateinit var desktopSurfaceView: SurfaceView
    private lateinit var imeCaptureView: ImeCaptureView
    private lateinit var imeBridge: ImeBridge

    private val touchGestures = TwoFingerGestures()
    private val trackpadController = TrackpadController()
    private var prevMouseButtonMask = 0

    private val showQuickMenuState = mutableStateOf(false)
    private val routingModeState = mutableStateOf(DisplayRoutingMode.INTERNAL_LAPTOP)
    private val externalMonitorLabelState = mutableStateOf<String?>(null)
    private val temporaryPhoneFallbackState = mutableStateOf(false)
    private val phoneScreenDimmedState = mutableStateOf(false)
    private val touchModeState = mutableStateOf(TouchInputMode.TOUCHSCREEN)
    private val showFloatingControlsState = mutableStateOf(false)

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
        override fun onDisplayAdded(displayId: Int) {
            // Clear temporary phone fallback as soon as a real external monitor is plugged in
            temporaryPhoneFallbackState.value = false
            refreshDisplayRouting()
        }

        override fun onDisplayRemoved(displayId: Int) = refreshDisplayRouting()
        override fun onDisplayChanged(displayId: Int) = refreshDisplayRouting()
    }

    private fun isInternalSurfaceActive(): Boolean {
        val mode = routingModeState.value
        return mode == DisplayRoutingMode.INTERNAL_LAPTOP || mode == DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!LinuxRuntime.isReady(this)) {
            startActivity(Intent(this, FrontEndActivity::class.java).putExtra(FrontEndActivity.EXTRA_SKIP_AUTOBOOT, true))
            finish()
            return
        }

        if (SessionState.state.value.phase == SessionPhase.FAILED) {
            SessionState.reset()
        }

        touchModeState.value = SessionPrefs.touchInputMode(this)
        showFloatingControlsState.value = SessionPrefs.showFloatingControls(this)
        val (initialMode, initialExt) = evaluateCurrentRouting()
        routingModeState.value = initialMode
        externalMonitorLabelState.value = initialExt?.let { DeXEnvironmentDetector.describeDisplay(it) }

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
                    if (isInternalSurfaceActive()) {
                        bindInternalSurface(holder)
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (isInternalSurfaceActive() && width > 0 && height > 0) {
                        val (outW, outH) = computeDesktopOutputSize(width, height)
                        val geom = SessionPrefs.computeCompositorGeometry(this@SessionActivity, outW, outH)
                        val hz = selectBestRefreshRate()
                        CompositorHost.attachSurface(
                            holder.surface,
                            geom.compositorWidth,
                            geom.compositorHeight,
                            hz,
                            geom.compositorScale,
                        )
                        ensureSessionStarted(outW, outH, hz)
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    if (isInternalSurfaceActive()) {
                        CompositorHost.detachSurface()
                    }
                }
            })
            setOnTouchListener { v, event -> handleMotionInput(v, event) }
            setOnGenericMotionListener { v, event -> handleGenericPointer(v, event) }
        }

        imeCaptureView = ImeCaptureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(2, 2, Gravity.TOP or Gravity.START)
            alpha = 0f
        }
        imeBridge = ImeBridge(
            activity = this,
            captureView = imeCaptureView,
            imeStateFile = File(LinuxRuntime.sessionDir(this), "ime.json"),
        )

        val overlayCompose = ComposeView(this).apply {
            setContent {
                val imeVisible by imeBridge.imeVisible.collectAsState()
                LinuxDroidTheme {
                    SessionOverlayUi(
                        routingMode = routingModeState.value,
                        externalMonitorLabel = externalMonitorLabelState.value,
                        phoneDimmed = phoneScreenDimmedState.value,
                        showQuickMenu = showQuickMenuState.value,
                        showFloatingControls = showFloatingControlsState.value,
                        imeVisible = imeVisible,
                        touchMode = touchModeState.value,
                        externalOnlyPref = SessionPrefs.externalDisplayMode(this@SessionActivity) == ExternalDisplayMode.EXTERNAL_ONLY,
                        onDismissQuickMenu = {
                            showQuickMenuState.value = false
                            hideSystemBarsIfFullscreen()
                        },
                        onOpenQuickMenu = {
                            setPhoneDimmed(false)
                            showQuickMenuState.value = true
                        },
                        onTogglePhoneDimmed = {
                            setPhoneDimmed(!phoneScreenDimmedState.value)
                        },
                        onUsePhoneScreenTemporary = {
                            temporaryPhoneFallbackState.value = true
                            refreshDisplayRouting()
                        },
                        onToggleExternalOnlyMode = {
                            val cur = SessionPrefs.externalDisplayMode(this@SessionActivity)
                            val next = if (cur == ExternalDisplayMode.EXTERNAL_ONLY) {
                                ExternalDisplayMode.PHONE_ONLY
                            } else {
                                ExternalDisplayMode.EXTERNAL_ONLY
                            }
                            SessionPrefs.setExternalDisplayMode(this@SessionActivity, next)
                            temporaryPhoneFallbackState.value = false
                            showQuickMenuState.value = false
                            setPhoneDimmed(false)
                            refreshDisplayRouting()
                        },
                        onToggleKeyboard = {
                            val wasSheetOpen = showQuickMenuState.value
                            showQuickMenuState.value = false
                            if (wasSheetOpen) {
                                imeBridge.showKeyboardManual()
                            } else {
                                imeBridge.toggleKeyboard()
                            }
                        },
                        onToggleFloatingControls = {
                            val next = !showFloatingControlsState.value
                            showFloatingControlsState.value = next
                            SessionPrefs.setShowFloatingControls(this@SessionActivity, next)
                            showQuickMenuState.value = false
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
                            setPhoneDimmed(false)
                            startActivity(Intent(this@SessionActivity, TerminalActivity::class.java))
                        },
                        onTogglePause = {
                            showQuickMenuState.value = false
                            SessionService.togglePause(this@SessionActivity)
                        },
                        onOpenDashboard = {
                            showQuickMenuState.value = false
                            setPhoneDimmed(false)
                            navigateBackToDashboard()
                        },
                        onRetryBoot = { safeMode ->
                            retryBootSession(safeMode = safeMode)
                        },
                        onSessionStoppedAfterRun = {
                            setPhoneDimmed(false)
                            navigateBackToDashboard()
                        },
                        onShutdown = {
                            showQuickMenuState.value = false
                            setPhoneDimmed(false)
                            SessionService.shutdown(this@SessionActivity)
                            navigateBackToDashboard()
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

        lifecycleScope.launch {
            SessionState.state.collect { snap ->
                externalPresentation?.updateBootOverlay(snap)
            }
        }
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
        touchModeState.value = SessionPrefs.touchInputMode(this)
        showFloatingControlsState.value = SessionPrefs.showFloatingControls(this)
        applyWindowAndDisplayMode()
        imeCaptureView.requestFocus()
        if (isInternalSurfaceActive()) {
            val holder = desktopSurfaceView.holder
            if (holder.surface != null && holder.surface.isValid) {
                bindInternalSurface(holder)
            }
        }
    }

    override fun onStop() {
        vsyncRunning = false
        Choreographer.getInstance().removeFrameCallback(vsyncCallback)
        imeBridge.stop()
        setPhoneDimmed(false)
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

    private fun setPhoneDimmed(dimmed: Boolean) {
        phoneScreenDimmedState.value = dimmed
        val attrs = window.attributes
        attrs.screenBrightness = if (dimmed) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val kc = event.keyCode
        if (kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN || kc == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event)
        }

        if (kc == KeyEvent.KEYCODE_ESCAPE &&
            event.isCtrlPressed &&
            event.isAltPressed &&
            event.isShiftPressed
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                setPhoneDimmed(false)
                showQuickMenuState.value = !showQuickMenuState.value
            }
            return true
        }

        if (kc == KeyEvent.KEYCODE_BACK && (event.source and InputDevice.SOURCE_MOUSE) == 0) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                if (imeBridge.imeVisible.value) {
                    imeBridge.hideKeyboard()
                } else {
                    setPhoneDimmed(false)
                    showQuickMenuState.value = !showQuickMenuState.value
                }
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.deviceId > 0 && kc in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            val dev = event.device
            if (dev != null && !dev.isVirtual && dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                imeBridge.physicalKeyboardTyped = true
            }
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
        if (routingModeState.value == DisplayRoutingMode.WAITING_FOR_EXTERNAL_MONITOR) {
            return false
        }
        // When external monitor output is active, the phone screen acts as a precision multi-touch trackpad
        if (routingModeState.value == DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP ||
            touchModeState.value == TouchInputMode.TRACKPAD
        ) {
            return trackpadController.onTouchEvent(event)
        }
        return touchGestures.onTouchEvent(event, view.width, view.height)
    }

    private fun handleExternalSurfaceTouch(view: View, event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) {
            return handleMouseTouchEvent(view, event)
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
        val geom = SessionPrefs.computeCompositorGeometry(this, outW, outH)
        val hz = selectBestRefreshRate()
        CompositorHost.ensureStarted(
            context = this,
            xdgRuntimeDir = LinuxRuntime.runtimeDir(this),
            initialWidth = geom.compositorWidth,
            initialHeight = geom.compositorHeight,
            refreshHz = hz,
            desktopScale = geom.compositorScale,
            surface = holder.surface,
        )
        CompositorHost.attachSurface(
            holder.surface,
            geom.compositorWidth,
            geom.compositorHeight,
            hz,
            geom.compositorScale,
        )
        ensureSessionStarted(outW, outH, hz)
    }

    private fun retryBootSession(safeMode: Boolean) {
        if (safeMode) {
            SessionPrefs.setGpuMode(this, GpuDriverMode.SOFTWARE)
            SessionPrefs.setProotFastPath(this, false)
            SessionPrefs.setProotNoSeccomp(this, true)
        }
        SessionState.reset()

        val extDisp = externalPresentation?.display
        if (routingModeState.value == DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP && extDisp != null) {
            val mode = extDisp.mode
            val (outW, outH) = SessionPrefs.resolutionPreset(this).resolveForSurface(mode.physicalWidth, mode.physicalHeight)
            val geom = SessionPrefs.computeCompositorGeometry(this, outW, outH)
            val hz = selectBestRefreshRate(extDisp)
            val extHolder = externalPresentation?.surfaceHolder
            if (extHolder != null && extHolder.surface != null && extHolder.surface.isValid) {
                CompositorHost.attachSurface(
                    extHolder.surface,
                    geom.compositorWidth,
                    geom.compositorHeight,
                    hz,
                    geom.compositorScale,
                )
            }
            SessionService.restart(this, outW, outH, hz, forceCompat = safeMode)
            return
        }

        val holder = desktopSurfaceView.holder
        val w = desktopSurfaceView.width.takeIf { it > 0 } ?: 1920
        val h = desktopSurfaceView.height.takeIf { it > 0 } ?: 1080
        val (outW, outH) = computeDesktopOutputSize(w, h)
        val geom = SessionPrefs.computeCompositorGeometry(this, outW, outH)
        val hz = selectBestRefreshRate()
        if (isInternalSurfaceActive() && holder.surface != null && holder.surface.isValid) {
            CompositorHost.attachSurface(
                holder.surface,
                geom.compositorWidth,
                geom.compositorHeight,
                hz,
                geom.compositorScale,
            )
        }
        SessionService.restart(this, outW, outH, hz, forceCompat = safeMode)
    }

    private fun navigateBackToDashboard() {
        startActivity(
            Intent(this, FrontEndActivity::class.java)
                .putExtra(FrontEndActivity.EXTRA_SKIP_AUTOBOOT, true)
        )
        finish()
    }

    private fun evaluateCurrentRouting(): Pair<DisplayRoutingMode, Display?> {
        val override = if (temporaryPhoneFallbackState.value) ExternalDisplayMode.PHONE_ONLY else null
        return DeXEnvironmentDetector.detectForActivity(this, overrideExternalMode = override)
    }

    private fun refreshDisplayRouting() {
        val (mode, extDisplay) = evaluateCurrentRouting()
        val prevMode = routingModeState.value
        routingModeState.value = mode
        externalMonitorLabelState.value = extDisplay?.let { DeXEnvironmentDetector.describeDisplay(it) }

        when (mode) {
            DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP -> {
                if (extDisplay != null && externalPresentation?.display?.displayId != extDisplay.displayId) {
                    dismissExternalPresentation()
                    CompositorHost.detachSurface()
                    externalPresentation = ExternalMonitorPresentation(this, extDisplay).also { pres ->
                        runCatching {
                            pres.show()
                            pres.updateBootOverlay(SessionState.state.value)
                        }.onFailure {
                            externalPresentation = null
                            routingModeState.value = DisplayRoutingMode.INTERNAL_LAPTOP
                        }
                    }
                }
            }

            DisplayRoutingMode.WAITING_FOR_EXTERNAL_MONITOR -> {
                setPhoneDimmed(false)
                dismissExternalPresentation()
                CompositorHost.detachSurface()
            }

            DisplayRoutingMode.INTERNAL_LAPTOP,
            DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW -> {
                setPhoneDimmed(false)
                val hadExternal = externalPresentation != null ||
                    prevMode == DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP ||
                    prevMode == DisplayRoutingMode.WAITING_FOR_EXTERNAL_MONITOR
                dismissExternalPresentation()
                if (hadExternal) {
                    val holder = desktopSurfaceView.holder
                    if (holder.surface != null && holder.surface.isValid) {
                        bindInternalSurface(holder)
                    }
                }
            }
        }
    }

    private fun dismissExternalPresentation() {
        runCatching { externalPresentation?.dismiss() }
        externalPresentation = null
    }

    private fun computeDesktopOutputSize(surfaceW: Int, surfaceH: Int): Pair<Int, Int> {
        if (routingModeState.value == DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW) {
            return (surfaceW and 1.inv()).coerceAtLeast(640) to (surfaceH and 1.inv()).coerceAtLeast(480)
        }
        return SessionPrefs.resolutionPreset(this).resolveForSurface(surfaceW, surfaceH)
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
        val snap = SessionState.state.value
        val phase = snap.phase
        if (phase == SessionPhase.FAILED || phase == SessionPhase.STOPPING) return
        val sigChanged = snap.activeConfigSignature != null &&
            snap.activeConfigSignature != SessionPrefs.currentConfigSignature(this)
        if (!SessionState.isRunningOrPaused || SessionState.isPaused || sigChanged) {
            SessionService.startOrResume(this, width, height, refreshHz)
        }
    }

    private fun applyWindowAndDisplayMode() {
        val mode = routingModeState.value
        requestedOrientation = if (mode == DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            SessionPrefs.orientation(this).androidOrientation
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (mode != DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW) {
            hideSystemBarsIfFullscreen()
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }

    private fun hideSystemBarsIfFullscreen() {
        if (routingModeState.value == DisplayRoutingMode.DEX_OR_FREEFORM_WINDOW) return
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
        var surfaceHolder: SurfaceHolder? = null
            private set
        private var statusBanner: TextView? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            val container = FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
            }

            val extSurfaceView = SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        surfaceHolder = holder
                        val mode = display.mode
                        val (outW, outH) = SessionPrefs.resolutionPreset(this@SessionActivity)
                            .resolveForSurface(mode.physicalWidth, mode.physicalHeight)
                        val geom = SessionPrefs.computeCompositorGeometry(this@SessionActivity, outW, outH)
                        val hz = selectBestRefreshRate(display)
                        CompositorHost.ensureStarted(
                            context = this@SessionActivity,
                            xdgRuntimeDir = LinuxRuntime.runtimeDir(this@SessionActivity),
                            initialWidth = geom.compositorWidth,
                            initialHeight = geom.compositorHeight,
                            refreshHz = hz,
                            desktopScale = geom.compositorScale,
                            surface = holder.surface,
                        )
                        CompositorHost.attachSurface(
                            holder.surface,
                            geom.compositorWidth,
                            geom.compositorHeight,
                            hz,
                            geom.compositorScale,
                        )
                        ensureSessionStarted(outW, outH, hz)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        surfaceHolder = holder
                        if (width > 0 && height > 0) {
                            val (outW, outH) = SessionPrefs.resolutionPreset(this@SessionActivity)
                                .resolveForSurface(width, height)
                            val geom = SessionPrefs.computeCompositorGeometry(this@SessionActivity, outW, outH)
                            val hz = selectBestRefreshRate(display)
                            CompositorHost.attachSurface(
                                holder.surface,
                                geom.compositorWidth,
                                geom.compositorHeight,
                                hz,
                                geom.compositorScale,
                            )
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        if (surfaceHolder === holder) surfaceHolder = null
                        if (routingModeState.value == DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP) {
                            CompositorHost.detachSurface()
                        }
                    }
                })
                setOnTouchListener { v, ev -> handleExternalSurfaceTouch(v, ev) }
                setOnGenericMotionListener { v, ev -> handleGenericPointer(v, ev) }
            }

            val density = context.resources.displayMetrics.density
            val banner = TextView(context).apply {
                setTextColor(0xFFE5EDF5.toInt())
                textSize = 15f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                val padH = (24 * density).toInt()
                val padV = (14 * density).toInt()
                setPadding(padH, padV, padH, padV)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f * density
                    setColor(0xE6131B24.toInt())
                    setStroke((1 * density).toInt().coerceAtLeast(1), 0xFF334155.toInt())
                }
                text = "LinuxDroid • Starting Linux Desktop..."
            }
            statusBanner = banner

            container.addView(
                extSurfaceView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            container.addView(
                banner,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                )
            )
            setContentView(container)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            return this@SessionActivity.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
        }

        fun updateBootOverlay(snap: SessionSnapshot) {
            val view = statusBanner ?: return
            if (snap.firstFrameReady && snap.phase == SessionPhase.RUNNING) {
                view.visibility = View.GONE
            } else if (snap.phase == SessionPhase.FAILED) {
                view.visibility = View.VISIBLE
                view.text = "LinuxDroid Startup Failed — Check Phone Screen"
            } else {
                view.visibility = View.VISIBLE
                val step = snap.bootStep.coerceIn(1, snap.bootTotalSteps.coerceAtLeast(1))
                val total = snap.bootTotalSteps.coerceAtLeast(1)
                view.text = "LinuxDroid • Step $step/$total\n${snap.statusText}"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SessionOverlayUi(
    routingMode: DisplayRoutingMode,
    externalMonitorLabel: String?,
    phoneDimmed: Boolean,
    showQuickMenu: Boolean,
    showFloatingControls: Boolean,
    imeVisible: Boolean,
    touchMode: TouchInputMode,
    externalOnlyPref: Boolean,
    onDismissQuickMenu: () -> Unit,
    onOpenQuickMenu: () -> Unit,
    onTogglePhoneDimmed: () -> Unit,
    onUsePhoneScreenTemporary: () -> Unit,
    onToggleExternalOnlyMode: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleFloatingControls: () -> Unit,
    onToggleTouchMode: () -> Unit,
    onCycleOrientation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onTogglePause: () -> Unit,
    onOpenDashboard: () -> Unit,
    onRetryBoot: (safeMode: Boolean) -> Unit,
    onSessionStoppedAfterRun: () -> Unit,
    onShutdown: () -> Unit,
) {
    val snap by SessionState.state.collectAsState()
    var sawActiveDesktop by remember { mutableStateOf(false) }
    var elapsedSec by remember { mutableIntStateOf(0) }

    LaunchedEffect(snap.phase, snap.firstFrameReady) {
        if (snap.firstFrameReady && (snap.phase == SessionPhase.RUNNING || snap.phase == SessionPhase.PAUSED)) {
            sawActiveDesktop = true
        } else if (sawActiveDesktop && snap.phase == SessionPhase.STOPPED) {
            onSessionStoppedAfterRun()
        }
    }

    LaunchedEffect(snap.phase, snap.firstFrameReady, snap.startedAtMs) {
        if (!snap.firstFrameReady && snap.phase != SessionPhase.FAILED) {
            val anchor = if (snap.startedAtMs > 0L) snap.startedAtMs else System.currentTimeMillis()
            while (true) {
                elapsedSec = ((System.currentTimeMillis() - anchor) / 1000L).toInt().coerceAtLeast(0)
                delay(500L)
            }
        } else {
            elapsedSec = 0
        }
    }

    when {
        routingMode == DisplayRoutingMode.WAITING_FOR_EXTERNAL_MONITOR -> {
            WaitingForExternalMonitorOverlay(
                onUsePhoneScreenTemporary = onUsePhoneScreenTemporary,
                onOpenDashboard = onOpenDashboard,
            )
        }

        snap.phase == SessionPhase.FAILED -> {
            BootFailedOverlay(
                snap = snap,
                onRetryBoot = onRetryBoot,
                onOpenTerminal = onOpenTerminal,
                onOpenDashboard = onOpenDashboard,
            )
        }

        !snap.firstFrameReady -> {
            BootProgressOverlay(
                snap = snap,
                elapsedSec = elapsedSec,
                externalMonitorLabel = if (routingMode == DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP) externalMonitorLabel else null,
                onRetryBoot = onRetryBoot,
                onOpenDashboard = onOpenDashboard,
            )
        }

        routingMode == DisplayRoutingMode.EXTERNAL_MONITOR_HOTSWAP -> {
            ExternalMonitorTrackpadOverlay(
                externalMonitorLabel = externalMonitorLabel ?: "External Display",
                phoneDimmed = phoneDimmed,
                onTogglePhoneDimmed = onTogglePhoneDimmed,
                onToggleKeyboard = onToggleKeyboard,
                onOpenTerminal = onOpenTerminal,
                onOpenQuickMenu = onOpenQuickMenu,
            )
        }

        showFloatingControls -> {
            FloatingSessionControls(
                imeVisible = imeVisible,
                onToggleKeyboard = onToggleKeyboard,
                onOpenQuickMenu = onOpenQuickMenu,
            )
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                QuickMenuAction(Icons.Outlined.Keyboard, "Show Android On-Screen Keyboard", onToggleKeyboard)
                QuickMenuAction(
                    Icons.Outlined.VisibilityOff,
                    if (showFloatingControls) "Hide Floating Keyboard & Menu Pill" else "Show Floating Keyboard & Menu Pill",
                    onToggleFloatingControls,
                )
                QuickMenuAction(
                    if (touchMode == TouchInputMode.TOUCHSCREEN) Icons.Outlined.Mouse else Icons.Outlined.TouchApp,
                    if (touchMode == TouchInputMode.TOUCHSCREEN) "Switch to Virtual Trackpad" else "Switch to Direct Touchscreen",
                    onToggleTouchMode,
                )
                QuickMenuAction(
                    Icons.Outlined.Monitor,
                    if (externalOnlyPref) "Disable External Screen Only Mode" else "Enable External Screen Only Mode",
                    onToggleExternalOnlyMode,
                )
                QuickMenuAction(Icons.Outlined.ScreenRotation, "Cycle Screen Orientation", onCycleOrientation)
                QuickMenuAction(Icons.Outlined.Terminal, "Open Linux Terminal", onOpenTerminal)
                QuickMenuAction(
                    Icons.Outlined.Refresh,
                    "Restart Linux Session (Apply Settings)",
                    {
                        onDismissQuickMenu()
                        onRetryBoot(false)
                    },
                )
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

/**
 * Fixed-geometry, reassuring startup overlay with a live 6-step readiness checklist.
 * Automatically transitions straight to the Linux desktop as soon as the first display frame is ready.
 */
@Composable
private fun BootProgressOverlay(
    snap: SessionSnapshot,
    elapsedSec: Int,
    externalMonitorLabel: String?,
    onRetryBoot: (safeMode: Boolean) -> Unit,
    onOpenDashboard: () -> Unit,
) {
    val curStep = snap.bootStep.coerceIn(1, snap.bootTotalSteps.coerceAtLeast(1))
    val totalSteps = snap.bootTotalSteps.coerceAtLeast(1)
    val animatedProgress by animateFloatAsState(
        targetValue = curStep.toFloat() / totalSteps.toFloat(),
        label = "bootStepProgress",
    )
    val stageTitle = if (snap.phase == SessionPhase.STOPPED || snap.phase == SessionPhase.STOPPING) {
        "Preparing display surface & session service..."
    } else {
        snap.statusText
    }
    val metaLine = remember(externalMonitorLabel, snap.activeGpuSummary, snap.resolutionText) {
        buildList {
            if (!externalMonitorLabel.isNullOrEmpty()) add("External: $externalMonitorLabel")
            if (snap.activeGpuSummary.isNotEmpty()) add(snap.activeGpuSummary)
            if (snap.resolutionText.isNotEmpty()) add(snap.resolutionText)
        }.joinToString(" • ").ifEmpty { "Initializing ARM64 container runtime..." }
    }

    val bootChecklist = remember {
        listOf(
            1 to "Display surface & audio bridge ready",
            2 to "Wayland compositor & X11 socket online",
            3 to "SoC GPU hardware acceleration configured",
            4 to "Ubuntu ARM64 container environment mounted",
            5 to "Starting desktop window manager & panel",
            6 to "Waiting for desktop screen output (switches automatically)",
        )
    }
    val checklistScroll = rememberScrollState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090E14))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val cardWidth = minOf(maxWidth, 600.dp)
        val cardHeight = minOf(maxHeight, 344.dp).coerceAtLeast(220.dp)

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.size(width = cardWidth, height = cardHeight),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Fixed Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = if (externalMonitorLabel != null) {
                                "Starting on External Screen"
                            } else {
                                "Starting Linux Workstation"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = "Step $curStep/$totalSteps • ${elapsedSec}s",
                            style = MaterialTheme.typography.labelMedium.merge(
                                TextStyle(fontFeatureSettings = "tnum")
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                // Smooth Progress Bar
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )

                // Fixed 2-line Status & Hardware Strip (never wraps or expands)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stageTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = metaLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Fixed-Height Scrollable Readiness Checklist (calm, reassuring progress instead of raw console logs)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0B1118),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(checklistScroll)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for ((stepNum, label) in bootChecklist) {
                            val isDone = curStep > stepNum
                            val isCurrent = curStep == stepNum
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                when {
                                    isDone -> Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    isCurrent -> CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(15.dp),
                                    )
                                    else -> Box(
                                        modifier = Modifier
                                            .size(15.dp)
                                            .padding(3.dp)
                                            .background(
                                                color = MaterialTheme.colorScheme.outline,
                                                shape = RoundedCornerShape(8.dp),
                                            )
                                    )
                                }
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    color = when {
                                        isDone -> MaterialTheme.colorScheme.secondary
                                        isCurrent -> MaterialTheme.colorScheme.onSurface
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // Fixed Footer Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (elapsedSec >= 18) {
                            "First startup initializes caches — switches to desktop automatically"
                        } else {
                            "Switches to desktop automatically when screen output is ready"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    TextButton(
                        onClick = onOpenDashboard,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("Dashboard")
                    }
                }
            }
        }
    }
}

@Composable
private fun BootFailedOverlay(
    snap: SessionSnapshot,
    onRetryBoot: (safeMode: Boolean) -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenDashboard: () -> Unit,
) {
    val logScrollState = rememberScrollState()
    val logText = remember(snap.bootLogTail) {
        if (snap.bootLogTail.isEmpty()) "No log lines captured." else snap.bootLogTail.joinToString("\n")
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090E14))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val cardWidth = minOf(maxWidth, 620.dp)
        val cardHeight = minOf(maxHeight, 360.dp).coerceAtLeast(240.dp)

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)),
            modifier = Modifier.size(width = cardWidth, height = cardHeight),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(26.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Linux Desktop Startup Failed",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = snap.errorMessage ?: "The Linux session exited before presenting a desktop frame.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF060A0F),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(logScrollState)
                            .padding(10.dp),
                    ) {
                        Text(
                            text = logText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = Color(0xFFD0D8E4),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { onRetryBoot(true) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Safe Mode")
                    }
                    OutlinedButton(
                        onClick = { onRetryBoot(false) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Text("Retry")
                    }
                    OutlinedButton(
                        onClick = onOpenTerminal,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Terminal")
                    }
                    TextButton(onClick = onOpenDashboard) {
                        Text("Dashboard")
                    }
                }
            }
        }
    }
}

@Composable
private fun WaitingForExternalMonitorOverlay(
    onUsePhoneScreenTemporary: () -> Unit,
    onOpenDashboard: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090E14))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val cardWidth = minOf(maxWidth, 520.dp)
        val cardHeight = minOf(maxHeight, 250.dp).coerceAtLeast(210.dp)

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.size(width = cardWidth, height = cardHeight),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Monitor,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Waiting for External Screen",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "External Screen Only mode is enabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Text(
                    text = "Connect a USB-C / HDMI monitor (device must support USB-C DisplayPort Alt Mode video output) or Wireless Display. LinuxDroid will output the desktop fullscreen onto the external monitor without needing Samsung DeX.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onOpenDashboard) {
                        Text("Dashboard Settings")
                    }
                    Button(onClick = onUsePhoneScreenTemporary) {
                        Text("Use Phone Screen Now")
                    }
                }
            }
        }
    }
}

@Composable
private fun ExternalMonitorTrackpadOverlay(
    externalMonitorLabel: String,
    phoneDimmed: Boolean,
    onTogglePhoneDimmed: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenQuickMenu: () -> Unit,
) {
    if (phoneDimmed) {
        // Pure OLED black power-save trackpad surface (all touch gestures continue working underneath!)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(16.dp),
        ) {
            TextButton(
                onClick = onTogglePhoneDimmed,
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                Text(
                    text = "Wake Controls",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF475569),
                )
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090E14))
            .padding(20.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Monitor,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Output: $externalMonitorLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Mouse,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "External Screen Trackpad",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "1-finger move & tap • 2-finger scroll & right-click • 3-finger middle-click",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(
                onClick = onToggleKeyboard,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Outlined.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Keyboard")
            }
            OutlinedButton(
                onClick = onTogglePhoneDimmed,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Outlined.BrightnessLow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Dim Screen")
            }
            OutlinedButton(
                onClick = onOpenTerminal,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Terminal")
            }
            OutlinedButton(
                onClick = onOpenQuickMenu,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("Quick Menu")
            }
        }
    }
}

@Composable
private fun FloatingSessionControls(
    imeVisible: Boolean,
    onToggleKeyboard: () -> Unit,
    onOpenQuickMenu: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 10.dp, end = 12.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xB80E151E),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                IconButton(
                    onClick = onToggleKeyboard,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Keyboard,
                        contentDescription = "Toggle On-Screen Keyboard",
                        tint = if (imeVisible) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp),
                    )
                }
                IconButton(
                    onClick = onOpenQuickMenu,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Menu,
                        contentDescription = "Open Quick Menu",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickMenuAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
