package com.linuxdroid.app.input

import android.view.MotionEvent
import com.linuxdroid.app.wayland.InputSpace
import com.linuxdroid.app.wayland.WaylandCompositor
import kotlin.math.hypot

/**
 * Direct touchscreen handler for Laptop / Tablet touch mode:
 * - 1 finger: pointer/touch interaction (defers initial press briefly so 2-finger gestures never emit a stray click)
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

    private var pendingSingleDown = false
    private var singleDownSent = false
    private var singlePointerId = 0
    private var singleDownTimeMs = 0L
    private var singleDownRawX = 0f
    private var singleDownRawY = 0f
    private var singleDownIx = 0
    private var singleDownIy = 0

    private fun flushPendingSingleDown() {
        if (pendingSingleDown) {
            pendingSingleDown = false
            singleDownSent = true
            WaylandCompositor.nativeSendTouch(
                InputSpace.TOUCH_DOWN,
                singlePointerId,
                singleDownIx,
                singleDownIy,
            )
        }
    }

    fun onTouchEvent(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        val action = event.actionMasked
        val count = event.pointerCount

        if (count >= 2) {
            if (!twoFingerActive) {
                pendingSingleDown = false
                if (singleDownSent) {
                    singleDownSent = false
                    WaylandCompositor.nativeSendTouch(InputSpace.TOUCH_CANCEL, 0, 0, 0)
                }
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
                } else if (action == MotionEvent.ACTION_UP && event.eventTime - downTimeMs <= TAP_TIMEOUT_MS) {
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
            MotionEvent.ACTION_DOWN -> {
                val id = event.getPointerId(0)
                val ix = InputSpace.x(event.getX(0), viewWidth)
                val iy = InputSpace.y(event.getY(0), viewHeight)
                singlePointerId = id
                singleDownTimeMs = event.eventTime
                singleDownRawX = event.getX(0)
                singleDownRawY = event.getY(0)
                singleDownIx = ix
                singleDownIy = iy
                pendingSingleDown = true
                singleDownSent = false
                WaylandCompositor.nativeSendPointer(InputSpace.POINTER_HOVER, ix, iy)
            }
            MotionEvent.ACTION_MOVE -> {
                val x0 = event.getX(0)
                val y0 = event.getY(0)
                if (pendingSingleDown) {
                    val dist = hypot(x0 - singleDownRawX, y0 - singleDownRawY)
                    if (dist >= DRAG_SLOP_PX || event.eventTime - singleDownTimeMs >= SINGLE_PRESS_DELAY_MS) {
                        flushPendingSingleDown()
                    }
                }
                if (singleDownSent) {
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
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val ix = InputSpace.x(event.getX(idx), viewWidth)
                val iy = InputSpace.y(event.getY(idx), viewHeight)
                if (pendingSingleDown) {
                    flushPendingSingleDown()
                }
                if (singleDownSent) {
                    singleDownSent = false
                    WaylandCompositor.nativeSendTouch(
                        InputSpace.TOUCH_UP,
                        id,
                        ix,
                        iy,
                    )
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pendingSingleDown = false
                if (singleDownSent) {
                    singleDownSent = false
                    WaylandCompositor.nativeSendTouch(InputSpace.TOUCH_CANCEL, 0, 0, 0)
                }
            }
        }
        return true
    }

    companion object {
        private const val DRAG_SLOP_PX = 6f
        private const val SINGLE_PRESS_DELAY_MS = 65L
        private const val SCROLL_SLOP_PX = 14f
        private const val TAP_TIMEOUT_MS = 280L
    }
}
