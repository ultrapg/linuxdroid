package com.linuxdroid.app.input

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.linuxdroid.app.core.FileUtils
import com.linuxdroid.app.wayland.KeyCodes
import com.linuxdroid.app.wayland.WaylandCompositor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Bridges Android's native soft keyboard (Gboard, Samsung Keyboard, SwiftKey, etc.) to both
 * Wayland zwp_text_input_v3 clients AND nested KDE Plasma / XFCE4 / X11 windows
 * monitored by linuxdroid-ime (ime.json).
 */
class ImeCaptureView(context: Context) : View(context) {
    @Volatile
    var waylandTextInputActive: Boolean = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return object : BaseInputConnection(this, false) {
            private var composingText: String = ""

            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence {
                if (length <= 0 || composingText.isEmpty()) return ""
                return composingText.takeLast(length)
            }

            override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""

            override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
                return ExtractedText().apply {
                    text = composingText
                    startOffset = 0
                    selectionStart = composingText.length
                    selectionEnd = composingText.length
                }
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString().orEmpty()
                if (waylandTextInputActive) {
                    composingText = str
                    WaylandCompositor.textInputPreedit(str, str.length)
                } else {
                    val (backspaces, suffix) = computeComposingDiff(composingText, str)
                    repeat(backspaces) {
                        WaylandCompositor.nativeSendKey(KeyCodes.KEY_BACKSPACE, 1)
                        WaylandCompositor.nativeSendKey(KeyCodes.KEY_BACKSPACE, 0)
                    }
                    if (suffix.isNotEmpty()) KeyCodes.typeTextFallback(suffix)
                    composingText = str
                }
                return true
            }

            override fun finishComposingText(): Boolean {
                composingText = ""
                if (waylandTextInputActive) {
                    WaylandCompositor.textInputPreedit("", 0)
                }
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString().orEmpty()
                if (waylandTextInputActive) {
                    composingText = ""
                    WaylandCompositor.textInputCommit(str)
                } else {
                    if (composingText.isNotEmpty()) {
                        val (backspaces, suffix) = computeComposingDiff(composingText, str)
                        repeat(backspaces) {
                            WaylandCompositor.nativeSendKey(KeyCodes.KEY_BACKSPACE, 1)
                            WaylandCompositor.nativeSendKey(KeyCodes.KEY_BACKSPACE, 0)
                        }
                        composingText = ""
                        if (suffix.isNotEmpty()) KeyCodes.typeTextFallback(suffix)
                    } else if (str.isNotEmpty()) {
                        KeyCodes.typeTextFallback(str)
                    }
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                applySurroundingDelete(beforeLength, afterLength)
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                applySurroundingDelete(beforeLength, afterLength)
                return true
            }

            private fun applySurroundingDelete(beforeLength: Int, afterLength: Int) {
                composingText = ""
                if (waylandTextInputActive) {
                    WaylandCompositor.textInputDelete(beforeLength, afterLength)
                } else {
                    repeat(beforeLength.coerceIn(0, 64)) {
                        WaylandCompositor.nativeSendKey(KeyCodes.KEY_BACKSPACE, 1)
                        WaylandCompositor.nativeSendKey(KeyCodes.KEY_BACKSPACE, 0)
                    }
                    repeat(afterLength.coerceIn(0, 64)) {
                        WaylandCompositor.nativeSendKey(KeyCodes.KEY_DELETE, 1)
                        WaylandCompositor.nativeSendKey(KeyCodes.KEY_DELETE, 0)
                    }
                }
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                composingText = ""
                WaylandCompositor.nativeSendKey(KeyCodes.KEY_ENTER, 1)
                WaylandCompositor.nativeSendKey(KeyCodes.KEY_ENTER, 0)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && composingText.isNotEmpty()) {
                    composingText = composingText.dropLast(1)
                }
                val evdev = KeyCodes.fromKeyCode(event.keyCode)
                if (evdev >= 0) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        WaylandCompositor.nativeSendKey(evdev, 1)
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        WaylandCompositor.nativeSendKey(evdev, 0)
                    }
                    return true
                }
                @Suppress("DEPRECATION")
                if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) {
                    @Suppress("DEPRECATION")
                    commitText(event.characters, 1)
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    companion object {
        /**
         * Computes the minimal (backspaceCount, suffixToType) needed to transform [previous]
         * composed text into [updated] without erasing unchanged prefix characters.
         */
        internal fun computeComposingDiff(previous: String, updated: String): Pair<Int, String> {
            val maxCommon = minOf(previous.length, updated.length)
            var common = 0
            while (common < maxCommon && previous[common] == updated[common]) {
                common++
            }
            val backspaces = previous.length - common
            val suffix = updated.substring(common)
            return backspaces to suffix
        }
    }
}

class ImeBridge(
    private val activity: Activity,
    private val captureView: ImeCaptureView,
    private val imeStateFile: File,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastStamp = -1L
    private var lastSeq = -1
    private var lastGuestActive = false
    private var pendingFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null

    @Volatile
    var physicalKeyboardTyped: Boolean = false

    private val mutableImeVisible = MutableStateFlow(false)
    val imeVisible: StateFlow<Boolean> = mutableImeVisible

    init {
        ViewCompat.setOnApplyWindowInsetsListener(captureView) { _, insets ->
            val visible = insets.isVisible(WindowInsetsCompat.Type.ime()) ||
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
            mutableImeVisible.value = visible
            insets
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val mod = imeStateFile.lastModified()
            if (mod != 0L && mod != lastStamp) {
                lastStamp = mod
                val parsed = parseImeStateJson(FileUtils.readString(imeStateFile).orEmpty())
                if (parsed != null && (parsed.second != lastSeq || parsed.first != lastGuestActive)) {
                    val prev = lastGuestActive
                    lastGuestActive = parsed.first
                    lastSeq = parsed.second
                    if (parsed.first != prev || parsed.first) {
                        onAutoInputFocusChanged(parsed.first)
                    }
                }
            }
            mainHandler.postDelayed(this, 140L)
        }
    }

    fun start() {
        running = true
        lastStamp = -1L
        WaylandCompositor.setTextInputListener { enabled, _, _, _, _, _ ->
            captureView.waylandTextInputActive = enabled
            mainHandler.post { onAutoInputFocusChanged(enabled) }
        }
        mainHandler.post(pollRunnable)
    }

    fun stop() {
        running = false
        clearPendingFocusListener()
        mainHandler.removeCallbacksAndMessages(null)
        WaylandCompositor.setTextInputListener(null)
    }

    fun showKeyboardManual() {
        clearPendingFocusListener()
        if (captureView.hasWindowFocus()) {
            performShowIme()
            mainHandler.postDelayed({
                if (running && !mutableImeVisible.value && captureView.hasWindowFocus()) {
                    performShowIme()
                }
            }, 90L)
            return
        }

        // Window focus is temporarily held by a closing ModalBottomSheet / dialog; wait for
        // SessionActivity's window to regain focus before requesting the IME.
        val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (hasFocus) {
                    clearPendingFocusListener()
                    performShowIme()
                    mainHandler.postDelayed({
                        if (running && !mutableImeVisible.value) performShowIme()
                    }, 90L)
                }
            }
        }
        pendingFocusListener = listener
        captureView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        mainHandler.postDelayed({
            clearPendingFocusListener()
            if (running) performShowIme()
        }, 200L)
    }

    private fun performShowIme() {
        val imm = activity.getSystemService(InputMethodManager::class.java)
        captureView.isFocusable = true
        captureView.isFocusableInTouchMode = true
        captureView.requestFocus()
        imm?.restartInput(captureView)
        WindowInsetsControllerCompat(activity.window, captureView).show(WindowInsetsCompat.Type.ime())
        imm?.showSoftInput(captureView, 0)
    }

    fun hideKeyboard() {
        clearPendingFocusListener()
        WindowInsetsControllerCompat(activity.window, captureView).hide(WindowInsetsCompat.Type.ime())
        val imm = activity.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(captureView.windowToken, 0)
    }

    fun toggleKeyboard() {
        if (mutableImeVisible.value) {
            hideKeyboard()
        } else {
            showKeyboardManual()
        }
    }

    private fun clearPendingFocusListener() {
        val existing = pendingFocusListener ?: return
        pendingFocusListener = null
        runCatching {
            if (captureView.viewTreeObserver.isAlive) {
                captureView.viewTreeObserver.removeOnWindowFocusChangeListener(existing)
            }
        }
    }

    private fun onAutoInputFocusChanged(active: Boolean) {
        if (physicalKeyboardTyped) return
        if (active) {
            showKeyboardManual()
        } else if (!captureView.waylandTextInputActive && !lastGuestActive) {
            hideKeyboard()
        }
    }

    companion object {
        private val ACTIVE_TRUE_REGEX = Regex(""""(?:active|textField)"\s*:\s*true""")
        private val ACTIVE_KEY_REGEX = Regex(""""(?:active|textField)"\s*:\s*(true|false)""")
        private val SEQ_REGEX = Regex(""""seq"\s*:\s*(-?\d+)""")

        /** Returns (active, seq) from ime.json, accepting both "active" and "textField". */
        internal fun parseImeStateJson(raw: String): Pair<Boolean, Int>? {
            if (raw.isBlank() || !raw.contains('{')) return null
            if (!ACTIVE_KEY_REGEX.containsMatchIn(raw)) return null
            val active = ACTIVE_TRUE_REGEX.containsMatchIn(raw)
            val seq = SEQ_REGEX.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            return active to seq
        }
    }
}
