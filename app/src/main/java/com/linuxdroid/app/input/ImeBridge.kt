package com.linuxdroid.app.input

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.linuxdroid.app.core.FileUtils
import com.linuxdroid.app.wayland.KeyCodes
import com.linuxdroid.app.wayland.WaylandCompositor
import org.json.JSONObject
import java.io.File

/**
 * Bridges Android's native soft keyboard (Gboard, Samsung Keyboard, etc.) to both
 * Wayland zwp_text_input_v3 clients AND nested KDE Plasma / XFCE4 / X11 windows
 * monitored by linuxdroid-ime (ime.json).
 */
class ImeCaptureView(context: Context) : View(context) {
    @Volatile
    var waylandTextInputActive: Boolean = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composingLen = 0

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString().orEmpty()
                if (waylandTextInputActive) {
                    WaylandCompositor.textInputPreedit(str, str.length)
                } else {
                    if (composingLen > 0) {
                        repeat(composingLen) {
                            WaylandCompositor.nativeSendKey(KeyCodes.KEY_BACKSPACE, 1)
                            WaylandCompositor.nativeSendKey(KeyCodes.KEY_BACKSPACE, 0)
                        }
                    }
                    if (str.isNotEmpty()) KeyCodes.typeTextFallback(str)
                    composingLen = str.length
                }
                return true
            }

            override fun finishComposingText(): Boolean {
                composingLen = 0
                if (waylandTextInputActive) {
                    WaylandCompositor.textInputPreedit("", 0)
                }
                return true
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString().orEmpty()
                if (waylandTextInputActive) {
                    composingLen = 0
                    WaylandCompositor.textInputCommit(str)
                } else {
                    if (composingLen > 0) {
                        repeat(composingLen) {
                            WaylandCompositor.nativeSendKey(KeyCodes.KEY_BACKSPACE, 1)
                            WaylandCompositor.nativeSendKey(KeyCodes.KEY_BACKSPACE, 0)
                        }
                        composingLen = 0
                    }
                    if (str.isNotEmpty()) KeyCodes.typeTextFallback(str)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                composingLen = 0
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
                return true
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                WaylandCompositor.nativeSendKey(KeyCodes.KEY_ENTER, 1)
                WaylandCompositor.nativeSendKey(KeyCodes.KEY_ENTER, 0)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val evdev = KeyCodes.fromKeyCode(event.keyCode)
                if (evdev >= 0) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        WaylandCompositor.nativeSendKey(evdev, 1)
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        WaylandCompositor.nativeSendKey(evdev, 0)
                    }
                    return true
                }
                if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) {
                    commitText(event.characters, 1)
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }
}

class ImeBridge(
    private val context: Context,
    private val captureView: ImeCaptureView,
    private val imeStateFile: File,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastStamp = -1L
    private var lastGuestActive = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val mod = imeStateFile.lastModified()
            if (mod != 0L && mod != lastStamp) {
                lastStamp = mod
                val active = runCatching {
                    val json = JSONObject(FileUtils.readString(imeStateFile).orEmpty())
                    json.optBoolean("active", false)
                }.getOrDefault(false)
                if (active != lastGuestActive) {
                    lastGuestActive = active
                    onAutoInputFocusChanged(active)
                }
            }
            mainHandler.postDelayed(this, 180L)
        }
    }

    fun start() {
        running = true
        WaylandCompositor.setTextInputListener { enabled, _, _, _, _, _ ->
            captureView.waylandTextInputActive = enabled
            mainHandler.post { onAutoInputFocusChanged(enabled) }
        }
        mainHandler.post(pollRunnable)
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacks(pollRunnable)
        WaylandCompositor.setTextInputListener(null)
    }

    fun showKeyboardManual() {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        captureView.requestFocus()
        imm.showSoftInput(captureView, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(captureView.windowToken, 0)
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        captureView.requestFocus()
        @Suppress("DEPRECATION")
        imm.toggleSoftInputFromWindow(captureView.applicationWindowToken, InputMethodManager.SHOW_IMPLICIT, 0)
    }

    private fun hasHardwareKeyboard(): Boolean =
        context.resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
            context.resources.configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

    private fun onAutoInputFocusChanged(active: Boolean) {
        if (hasHardwareKeyboard()) return
        if (active) {
            showKeyboardManual()
        } else if (!captureView.waylandTextInputActive && !lastGuestActive) {
            hideKeyboard()
        }
    }
}
