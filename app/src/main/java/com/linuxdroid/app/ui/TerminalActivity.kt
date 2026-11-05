package com.linuxdroid.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.linuxdroid.app.session.SessionTerminal
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class TerminalActivity : ComponentActivity() {
    private var terminalView: TerminalView? = null
    private var userSession: TerminalSession? = null
    private var rootSession: TerminalSession? = null

    private var activeRoot by mutableStateOf(false)
    private var ctrlLatched by mutableStateOf(false)
    private var altLatched by mutableStateOf(false)
    private var fontSizePx = 34

    private val client = ClientImpl()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemeState.current.value = com.linuxdroid.app.session.SessionPrefs.appTheme(this)
        activeRoot = intent?.getBooleanExtra(EXTRA_AS_ROOT, false) == true

        setContent {
            LinuxDroidTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0B1117))
                        .systemBarsPadding()
                        .imePadding(),
                ) {
                    // Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                        Text(
                            text = "Linux Terminal",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = !activeRoot,
                            onClick = { switchShell(asRoot = false) },
                            label = { Text("User") },
                        )
                        FilterChip(
                            selected = activeRoot,
                            onClick = { switchShell(asRoot = true) },
                            label = { Text("Root") },
                        )
                        IconButton(onClick = { toggleSoftInput() }) {
                            Icon(Icons.Outlined.Keyboard, contentDescription = "Keyboard")
                        }
                    }

                    // Terminal Emulator Surface
                    AndroidView(
                        factory = { ctx ->
                            TerminalView(ctx, null).also { tv ->
                                terminalView = tv
                                tv.setBackgroundColor(android.graphics.Color.parseColor("#0B1117"))
                                tv.setTextSize(fontSizePx)
                                tv.setTerminalViewClient(client)
                                tv.isFocusable = true
                                tv.isFocusableInTouchMode = true
                                attachActiveSession(tv, activeRoot)
                                tv.requestFocus()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )

                    // Workstation Extra Keys Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ExtraKeyButton("ESC") { sendBytes("\u001b") }
                        ExtraKeyButton("TAB") { sendBytes("\t") }
                        ExtraKeyButton("CTRL", active = ctrlLatched) { ctrlLatched = !ctrlLatched }
                        ExtraKeyButton("ALT", active = altLatched) { altLatched = !altLatched }
                        ExtraKeyButton("↑") { sendBytes("\u001b[A") }
                        ExtraKeyButton("↓") { sendBytes("\u001b[B") }
                        ExtraKeyButton("←") { sendBytes("\u001b[D") }
                        ExtraKeyButton("→") { sendBytes("\u001b[C") }
                        ExtraKeyButton("-") { sendBytes("-") }
                        ExtraKeyButton("/") { sendBytes("/") }
                        ExtraKeyButton("|") { sendBytes("|") }
                        ExtraKeyButton("~") { sendBytes("~") }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun ExtraKeyButton(label: String, active: Boolean = false, onClick: () -> Unit) {
        OutlinedButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(34.dp),
            colors = if (active) {
                ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                ButtonDefaults.outlinedButtonColors()
            },
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }

    private fun switchShell(asRoot: Boolean) {
        activeRoot = asRoot
        val tv = terminalView ?: return
        attachActiveSession(tv, asRoot)
    }

    private fun attachActiveSession(tv: TerminalView, asRoot: Boolean) {
        val session = if (asRoot) {
            rootSession ?: SessionTerminal.createShellSession(this, true, client)?.also { rootSession = it }
        } else {
            userSession ?: SessionTerminal.createShellSession(this, false, client)?.also { userSession = it }
        }
        if (session != null) {
            tv.attachSession(session)
            tv.onScreenUpdated()
        }
    }

    private fun sendBytes(seq: String) {
        val cur = terminalView?.currentSession ?: return
        cur.write(seq)
        ctrlLatched = false
        altLatched = false
    }

    private fun toggleSoftInput() {
        val tv = terminalView ?: return
        tv.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.showSoftInput(tv, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroy() {
        runCatching { userSession?.finishIfRunning() }
        runCatching { rootSession?.finishIfRunning() }
        super.onDestroy()
    }

    private inner class ClientImpl : TerminalSessionClient, TerminalViewClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit

        override fun onSessionFinished(finishedSession: TerminalSession) {
            if (finishedSession == userSession) userSession = null
            if (finishedSession == rootSession) rootSession = null
            if (userSession == null && rootSession == null) finish()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("LinuxDroid Terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                session.write(clip.getItemAt(0).coerceToText(this@TerminalActivity).toString())
            }
        }

        override fun onBell(session: TerminalSession) {
            terminalView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        override fun onColorsChanged(changedSession: TerminalSession) {
            terminalView?.invalidate()
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            terminalView?.setTerminalCursorBlinkerRate(if (state) 600 else 0)
        }

        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message, e) }

        override fun onScale(scale: Float): Float {
            fontSizePx = (fontSizePx * scale).toInt().coerceIn(18, 72)
            terminalView?.setTextSize(fontSizePx)
            return 1f
        }

        override fun onSingleTapUp(e: MotionEvent) {
            toggleSoftInput()
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) = Unit

        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false

        override fun readControlKey(): Boolean = ctrlLatched.also { ctrlLatched = false }
        override fun readAltKey(): Boolean = altLatched.also { altLatched = false }
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

        override fun onEmulatorSet() {
            terminalView?.onScreenUpdated()
        }
    }

    companion object {
        const val EXTRA_AS_ROOT = "as_root"
    }
}
