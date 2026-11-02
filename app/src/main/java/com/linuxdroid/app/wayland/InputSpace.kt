package com.linuxdroid.app.wayland

object InputSpace {
    const val WIDTH = 1920
    const val HEIGHT = 1080

    const val POINTER_DOWN = 0
    const val POINTER_HOVER = 1
    const val POINTER_UP = 2

    const val TOUCH_DOWN = 0
    const val TOUCH_MOVE = 1
    const val TOUCH_UP = 2
    const val TOUCH_CANCEL = 3

    const val BTN_LEFT = 0x110
    const val BTN_RIGHT = 0x111
    const val BTN_MIDDLE = 0x112
    const val BTN_SIDE = 0x113
    const val BTN_EXTRA = 0x114

    fun x(position: Float, viewWidth: Int): Int = scale(position, viewWidth, WIDTH)

    fun y(position: Float, viewHeight: Int): Int = scale(position, viewHeight, HEIGHT)

    private fun scale(position: Float, extent: Int, size: Int): Int =
        if (extent <= 0) 0 else (position / extent * size).toInt().coerceIn(0, size - 1)
}
