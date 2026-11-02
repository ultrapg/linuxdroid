package com.linuxdroid.app.wayland

import android.view.KeyEvent

object KeyCodes {
    const val KEY_ESC = 1
    const val KEY_BACKSPACE = 14
    const val KEY_TAB = 15
    const val KEY_ENTER = 28
    const val KEY_LEFTCTRL = 29
    const val KEY_LEFTSHIFT = 42
    const val KEY_LEFTALT = 56
    const val KEY_UP = 103
    const val KEY_LEFT = 105
    const val KEY_RIGHT = 106
    const val KEY_DOWN = 108
    const val KEY_DELETE = 111
    const val KEY_LEFTMETA = 125

    fun fromKeyCode(kc: Int): Int = when (kc) {
        KeyEvent.KEYCODE_ESCAPE -> 1
        KeyEvent.KEYCODE_1 -> 2
        KeyEvent.KEYCODE_2 -> 3
        KeyEvent.KEYCODE_3 -> 4
        KeyEvent.KEYCODE_4 -> 5
        KeyEvent.KEYCODE_5 -> 6
        KeyEvent.KEYCODE_6 -> 7
        KeyEvent.KEYCODE_7 -> 8
        KeyEvent.KEYCODE_8 -> 9
        KeyEvent.KEYCODE_9 -> 10
        KeyEvent.KEYCODE_0 -> 11
        KeyEvent.KEYCODE_MINUS -> 12
        KeyEvent.KEYCODE_EQUALS -> 13
        KeyEvent.KEYCODE_DEL -> 14
        KeyEvent.KEYCODE_TAB -> 15
        KeyEvent.KEYCODE_Q -> 16
        KeyEvent.KEYCODE_W -> 17
        KeyEvent.KEYCODE_E -> 18
        KeyEvent.KEYCODE_R -> 19
        KeyEvent.KEYCODE_T -> 20
        KeyEvent.KEYCODE_Y -> 21
        KeyEvent.KEYCODE_U -> 22
        KeyEvent.KEYCODE_I -> 23
        KeyEvent.KEYCODE_O -> 24
        KeyEvent.KEYCODE_P -> 25
        KeyEvent.KEYCODE_LEFT_BRACKET -> 26
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 27
        KeyEvent.KEYCODE_ENTER -> 28
        KeyEvent.KEYCODE_CTRL_LEFT -> 29
        KeyEvent.KEYCODE_A -> 30
        KeyEvent.KEYCODE_S -> 31
        KeyEvent.KEYCODE_D -> 32
        KeyEvent.KEYCODE_F -> 33
        KeyEvent.KEYCODE_G -> 34
        KeyEvent.KEYCODE_H -> 35
        KeyEvent.KEYCODE_J -> 36
        KeyEvent.KEYCODE_K -> 37
        KeyEvent.KEYCODE_L -> 38
        KeyEvent.KEYCODE_SEMICOLON -> 39
        KeyEvent.KEYCODE_APOSTROPHE -> 40
        KeyEvent.KEYCODE_GRAVE -> 41
        KeyEvent.KEYCODE_SHIFT_LEFT -> 42
        KeyEvent.KEYCODE_BACKSLASH -> 43
        KeyEvent.KEYCODE_Z -> 44
        KeyEvent.KEYCODE_X -> 45
        KeyEvent.KEYCODE_C -> 46
        KeyEvent.KEYCODE_V -> 47
        KeyEvent.KEYCODE_B -> 48
        KeyEvent.KEYCODE_N -> 49
        KeyEvent.KEYCODE_M -> 50
        KeyEvent.KEYCODE_COMMA -> 51
        KeyEvent.KEYCODE_PERIOD -> 52
        KeyEvent.KEYCODE_SLASH -> 53
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 54
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 55
        KeyEvent.KEYCODE_ALT_LEFT -> 56
        KeyEvent.KEYCODE_SPACE -> 57
        KeyEvent.KEYCODE_CAPS_LOCK -> 58
        KeyEvent.KEYCODE_F1 -> 59
        KeyEvent.KEYCODE_F2 -> 60
        KeyEvent.KEYCODE_F3 -> 61
        KeyEvent.KEYCODE_F4 -> 62
        KeyEvent.KEYCODE_F5 -> 63
        KeyEvent.KEYCODE_F6 -> 64
        KeyEvent.KEYCODE_F7 -> 65
        KeyEvent.KEYCODE_F8 -> 66
        KeyEvent.KEYCODE_F9 -> 67
        KeyEvent.KEYCODE_F10 -> 68
        KeyEvent.KEYCODE_NUM_LOCK -> 69
        KeyEvent.KEYCODE_SCROLL_LOCK -> 70
        KeyEvent.KEYCODE_NUMPAD_7 -> 71
        KeyEvent.KEYCODE_NUMPAD_8 -> 72
        KeyEvent.KEYCODE_NUMPAD_9 -> 73
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 74
        KeyEvent.KEYCODE_NUMPAD_4 -> 75
        KeyEvent.KEYCODE_NUMPAD_5 -> 76
        KeyEvent.KEYCODE_NUMPAD_6 -> 77
        KeyEvent.KEYCODE_NUMPAD_ADD -> 78
        KeyEvent.KEYCODE_NUMPAD_1 -> 79
        KeyEvent.KEYCODE_NUMPAD_2 -> 80
        KeyEvent.KEYCODE_NUMPAD_3 -> 81
        KeyEvent.KEYCODE_NUMPAD_0 -> 82
        KeyEvent.KEYCODE_NUMPAD_DOT -> 83
        KeyEvent.KEYCODE_F11 -> 87
        KeyEvent.KEYCODE_F12 -> 88
        KeyEvent.KEYCODE_NUMPAD_ENTER -> 96
        KeyEvent.KEYCODE_CTRL_RIGHT -> 97
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 98
        KeyEvent.KEYCODE_SYSRQ -> 99
        KeyEvent.KEYCODE_ALT_RIGHT -> 100
        KeyEvent.KEYCODE_MOVE_HOME -> 102
        KeyEvent.KEYCODE_DPAD_UP -> 103
        KeyEvent.KEYCODE_PAGE_UP -> 104
        KeyEvent.KEYCODE_DPAD_LEFT -> 105
        KeyEvent.KEYCODE_DPAD_RIGHT -> 106
        KeyEvent.KEYCODE_MOVE_END -> 107
        KeyEvent.KEYCODE_DPAD_DOWN -> 108
        KeyEvent.KEYCODE_PAGE_DOWN -> 109
        KeyEvent.KEYCODE_INSERT -> 110
        KeyEvent.KEYCODE_FORWARD_DEL -> 111
        KeyEvent.KEYCODE_META_LEFT -> 125
        KeyEvent.KEYCODE_META_RIGHT -> 126
        KeyEvent.KEYCODE_MENU -> 127
        else -> -1
    }

    private const val SHIFTED_CHARS = "!@#$%^&*()_+{}|:\"<>?~"
    private const val PLAIN_CHARS = "1234567890-=[]\\;',./`"

    fun typeTextFallback(text: String) {
        var needsClipboard = false
        for (ch in text) {
            if (!typeSingleAscii(ch)) {
                needsClipboard = true
                break
            }
        }
        if (needsClipboard) {
            WaylandCompositor.setClipboardText(text)
            WaylandCompositor.nativeSendKey(KEY_LEFTCTRL, 1)
            WaylandCompositor.nativeSendKey(KEY_LEFTSHIFT, 1)
            WaylandCompositor.nativeSendKey(47 /* KEY_V */, 1)
            WaylandCompositor.nativeSendKey(47 /* KEY_V */, 0)
            WaylandCompositor.nativeSendKey(KEY_LEFTSHIFT, 0)
            WaylandCompositor.nativeSendKey(KEY_LEFTCTRL, 0)
        }
    }

    private fun typeSingleAscii(ch: Char): Boolean {
        if (ch == '\n' || ch == '\r') {
            tap(KEY_ENTER)
            return true
        }
        if (ch == '\t') {
            tap(KEY_TAB)
            return true
        }
        val shifted: Boolean
        val baseChar: Char
        if (ch in 'A'..'Z') {
            shifted = true
            baseChar = ch.lowercaseChar()
        } else {
            val idx = SHIFTED_CHARS.indexOf(ch)
            if (idx >= 0) {
                shifted = true
                baseChar = PLAIN_CHARS[idx]
            } else {
                shifted = false
                baseChar = ch
            }
        }
        val androidKc = keycodeForBaseChar(baseChar)
        if (androidKc == 0) return false
        val evdev = fromKeyCode(androidKc)
        if (evdev < 0) return false
        if (shifted) WaylandCompositor.nativeSendKey(KEY_LEFTSHIFT, 1)
        tap(evdev)
        if (shifted) WaylandCompositor.nativeSendKey(KEY_LEFTSHIFT, 0)
        return true
    }

    private fun tap(evdev: Int) {
        WaylandCompositor.nativeSendKey(evdev, 1)
        WaylandCompositor.nativeSendKey(evdev, 0)
    }

    private fun keycodeForBaseChar(ch: Char): Int = when {
        ch in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
        ch in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
        ch == '-' -> KeyEvent.KEYCODE_MINUS
        ch == '=' -> KeyEvent.KEYCODE_EQUALS
        ch == '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
        ch == ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
        ch == '\\' -> KeyEvent.KEYCODE_BACKSLASH
        ch == ';' -> KeyEvent.KEYCODE_SEMICOLON
        ch == '\'' -> KeyEvent.KEYCODE_APOSTROPHE
        ch == ',' -> KeyEvent.KEYCODE_COMMA
        ch == '.' -> KeyEvent.KEYCODE_PERIOD
        ch == '/' -> KeyEvent.KEYCODE_SLASH
        ch == '`' -> KeyEvent.KEYCODE_GRAVE
        ch == ' ' -> KeyEvent.KEYCODE_SPACE
        else -> 0
    }
}
