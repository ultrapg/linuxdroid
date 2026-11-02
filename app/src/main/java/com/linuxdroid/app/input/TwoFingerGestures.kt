package com.linuxdroid.app.input

import android.view.MotionEvent
import com.linuxdroid.app.wayland.InputSpace
import com.linuxdroid.app.wayland.WaylandCompositor
import kotlin.math.hypot

/**
 * Direct touchscreen handler for Laptop / Tablet touch mode:
 * - 1 finger: native Wayland multi-touch (wl_touch, with automatic pointer fallback for X11 apps)
 * - 2-finger tap: right-click at finger midpoint
 * - 2-finger drag: smooth two-finger touchpad scroll
 */
class TwoFingerGestures {
    private var twoFingerActive = false
    private var scrolling = false
    private var downTimeMs = 0L
    private var prevMidX = 0f
    private var prevMidY = 0f
    private var maxSpreadMove = 0f

    fun onTouchEvent(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        val action = event.actionMasked
        val count = event.pointerCount

        if (count >= 2) {
            if (!twoFingerActive) {
                // Cancel any active single-finger touch so the desktop doesn't register an accidental click
                WaylandCompositor.nativeSendTouch(InputSpace.TOUCH_CANCEL, 0, 0, 0)
                twoFingerActive = true
                scrolling = false
                downTimeMs = event.eventTime
                prevMidX = (event.getX(0) + event.getX(1)) * 0.5f
                prevMidY = (event.getY(0) + event.getY(1)) * 0.5f
                maxSpreadMove = 0f
                val ix = InputSpace.x(prevMidX, viewWidth)
                val iy = InputSpace.y(prevMidY, viewHeight)
                WaylandCompositor.nativeSendPointer(InputSpace.POINTER_HOVER, ix, iy)
                return true
            }

            val midX = (event.getX(0) + event.getX(1)) * 0.5f
            val midY = (event.getY(0) + event.getY(1)) * 0.5f
            val dx = midX - prevMidX
            val dy = midY - prevMidY
            val stepDist = hypot(dx, dy)
            maxSpreadMove += stepDist

            if (!scrolling && maxSpreadMove > SCROLL_SLOP_PX) {
                scrolling = true
            }
            if (scrolling) {
                val vert120 = (-dy * 2.4f).toInt()
                val horiz120 = (-dx * 2.4f).toInt()
                if (vert120 != 0 || horiz120 != 0) {
                    WaylandCompositor.sendFingerScroll(vert120, horiz120)
                }
            }
            prevMidX = midX
            prevMidY = midY
            return true
        }

        if (twoFingerActive) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (scrolling) {
                    WaylandCompositor.sendFingerScroll(0, 0)
                } else if (event.eventTime - downTimeMs <= TAP_TIMEOUT_MS) {
                    val ix = InputSpace.x(prevMidX, viewWidth)
                    val iy = InputSpace.y(prevMidY, viewHeight)
                    WaylandCompositor.nativeSendPointer(InputSpace.POINTER_HOVER, ix, iy)
                    WaylandCompositor.sendButton(InputSpace.BTN_RIGHT, true)
                    WaylandCompositor.sendButton(InputSpace.BTN_RIGHT, false)
                }
                twoFingerActive = false
                scrolling = false
            }
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                WaylandCompositor.nativeSendTouch(
                    InputSpace.TOUCH_DOWN,
                    id,
                    InputSpace.x(event.getX(idx), viewWidth),
                    InputSpace.y(event.getY(idx), viewHeight),
                )
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until count) {
                    val id = event.getPointerId(i)
                    WaylandCompositor.nativeSendTouch(
                        InputSpace.TOUCH_MOVE,
                        id,
                        InputSpace.x(event.getX(i), viewWidth),
                        InputSpace.y(event.getY(i), viewHeight),
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                WaylandCompositor.nativeSendTouch(
                    InputSpace.TOUCH_UP,
                    id,
                    InputSpace.x(event.getX(idx), viewWidth),
                    InputSpace.y(event.getY(idx), viewHeight),
                )
            }
            MotionEvent.ACTION_CANCEL -> {
                WaylandCompositor.nativeSendTouch(InputSpace.TOUCH_CANCEL, 0, 0, 0)
            }
        }
        return true
    }

    companion object {
        private const val SCROLL_SLOP_PX = 14f
        private const val TAP_TIMEOUT_MS = 280L
    }
}
