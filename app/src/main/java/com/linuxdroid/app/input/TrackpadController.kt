package com.linuxdroid.app.input

import android.view.MotionEvent
import com.linuxdroid.app.wayland.InputSpace
import com.linuxdroid.app.wayland.WaylandCompositor
import kotlin.math.hypot
import kotlin.math.min

/**
 * Laptop-style precision multi-touch trackpad controller:
 * - 1-finger move: accelerated relative cursor movement
 * - 1-finger tap: left-click; double-tap & hold: click-and-drag
 * - 2-finger tap: right-click
 * - 2-finger drag: smooth two-finger scrolling
 * - 3-finger tap: middle-click
 */
class TrackpadController(private val speedMultiplier: Float = 1.15f) {
    private var maxPointersInGesture = 0
    private var gestureStartMs = 0L
    private var totalMovePx = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var scrolling = false
    private var draggingLeft = false
    private var lastSingleTapUpMs = 0L

    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val count = event.pointerCount

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                maxPointersInGesture = 1
                gestureStartMs = event.eventTime
                totalMovePx = 0f
                scrolling = false
                lastX = event.getX(0)
                lastY = event.getY(0)
                if (event.eventTime - lastSingleTapUpMs <= DOUBLE_TAP_DRAG_MS) {
                    draggingLeft = true
                    WaylandCompositor.sendButton(InputSpace.BTN_LEFT, true)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointersInGesture = maxOf(maxPointersInGesture, count)
                if (draggingLeft) {
                    WaylandCompositor.sendButton(InputSpace.BTN_LEFT, false)
                    draggingLeft = false
                }
                if (count >= 2) {
                    lastX = (event.getX(0) + event.getX(1)) * 0.5f
                    lastY = (event.getY(0) + event.getY(1)) * 0.5f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (count == 1 && maxPointersInGesture == 1) {
                    val x = event.getX(0)
                    val y = event.getY(0)
                    val dx = x - lastX
                    val dy = y - lastY
                    lastX = x
                    lastY = y
                    val dist = hypot(dx, dy)
                    totalMovePx += dist
                    if (totalMovePx > MOVE_SLOP_PX || draggingLeft) {
                        val accel = min(2.4f, 0.85f + dist * 0.045f) * speedMultiplier
                        WaylandCompositor.sendPointerDelta(dx * accel, dy * accel)
                    }
                } else if (count >= 2) {
                    val midX = (event.getX(0) + event.getX(1)) * 0.5f
                    val midY = (event.getY(0) + event.getY(1)) * 0.5f
                    val dx = midX - lastX
                    val dy = midY - lastY
                    lastX = midX
                    lastY = midY
                    totalMovePx += hypot(dx, dy)
                    if (totalMovePx > SCROLL_SLOP_PX) {
                        scrolling = true
                        WaylandCompositor.sendFingerScroll((-dy * 2.5f).toInt(), (-dx * 2.5f).toInt())
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (count == 2) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = event.eventTime - gestureStartMs
                if (draggingLeft) {
                    WaylandCompositor.sendButton(InputSpace.BTN_LEFT, false)
                    draggingLeft = false
                } else if (scrolling) {
                    WaylandCompositor.sendFingerScroll(0, 0)
                    scrolling = false
                } else if (elapsed <= TAP_TIMEOUT_MS && totalMovePx <= TAP_SLOP_PX) {
                    when (maxPointersInGesture) {
                        1 -> {
                            WaylandCompositor.sendButton(InputSpace.BTN_LEFT, true)
                            WaylandCompositor.sendButton(InputSpace.BTN_LEFT, false)
                            lastSingleTapUpMs = event.eventTime
                        }
                        2 -> {
                            WaylandCompositor.sendButton(InputSpace.BTN_RIGHT, true)
                            WaylandCompositor.sendButton(InputSpace.BTN_RIGHT, false)
                        }
                        else -> {
                            WaylandCompositor.sendButton(InputSpace.BTN_MIDDLE, true)
                            WaylandCompositor.sendButton(InputSpace.BTN_MIDDLE, false)
                        }
                    }
                }
                maxPointersInGesture = 0
            }
            MotionEvent.ACTION_CANCEL -> {
                if (draggingLeft) {
                    WaylandCompositor.sendButton(InputSpace.BTN_LEFT, false)
                    draggingLeft = false
                }
                if (scrolling) {
                    WaylandCompositor.sendFingerScroll(0, 0)
                    scrolling = false
                }
                maxPointersInGesture = 0
            }
        }
        return true
    }

    companion object {
        private const val MOVE_SLOP_PX = 3f
        private const val SCROLL_SLOP_PX = 12f
        private const val TAP_SLOP_PX = 18f
        private const val TAP_TIMEOUT_MS = 260L
        private const val DOUBLE_TAP_DRAG_MS = 260L
    }
}
