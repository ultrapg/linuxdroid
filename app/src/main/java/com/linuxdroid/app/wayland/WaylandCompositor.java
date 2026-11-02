package com.linuxdroid.app.wayland;

import android.view.Surface;
import java.nio.charset.StandardCharsets;

public final class WaylandCompositor {
    static {
        System.loadLibrary("waylandcomp");
    }

    private WaylandCompositor() {}

    private static volatile Runnable firstFrameListener;

    public static void setFirstFrameListener(Runnable r) {
        firstFrameListener = r;
    }

    @SuppressWarnings("unused")
    static void onFirstFramePresented() {
        Runnable r = firstFrameListener;
        if (r != null) r.run();
    }

    public interface ClientListener {
        void onClientSurface(String window, String gpuName);
        void onClientFrame();
        default void onClientProgram(int pid, String program) {}
    }

    private static volatile ClientListener clientListener;

    public static void setClientListener(ClientListener l) {
        clientListener = l;
    }

    @SuppressWarnings("unused")
    static void onClientSurface(String window, String gpuName) {
        ClientListener l = clientListener;
        if (l != null) l.onClientSurface(window, gpuName);
    }

    @SuppressWarnings("unused")
    static void onClientFrame() {
        ClientListener l = clientListener;
        if (l != null) l.onClientFrame();
    }

    @SuppressWarnings("unused")
    static void onClientProgram(int pid, String program) {
        ClientListener l = clientListener;
        if (l != null) l.onClientProgram(pid, program != null ? program : "");
    }

    public interface PointerLockListener {
        void onPointerLock(boolean locked, int x, int y);
    }

    private static volatile PointerLockListener pointerLockListener;

    public static void setPointerLockListener(PointerLockListener l) {
        pointerLockListener = l;
    }

    @SuppressWarnings("unused")
    static void onPointerLock(boolean locked, int x, int y) {
        PointerLockListener l = pointerLockListener;
        if (l != null) l.onPointerLock(locked, x, y);
    }

    public interface ClipboardListener {
        void onGuestClipboardText(String text);
    }

    private static volatile ClipboardListener clipboardListener;

    public static void setClipboardListener(ClipboardListener l) {
        clipboardListener = l;
    }

    @SuppressWarnings("unused")
    static void onClipboardText(byte[] utf8) {
        ClipboardListener l = clipboardListener;
        if (l != null && utf8 != null) {
            l.onGuestClipboardText(new String(utf8, StandardCharsets.UTF_8));
        }
    }

    public interface TextInputListener {
        void onTextInput(boolean enabled, String program, int x, int y, int w, int h);
    }

    private static volatile TextInputListener textInputListener;

    public static void setTextInputListener(TextInputListener l) {
        textInputListener = l;
    }

    @SuppressWarnings("unused")
    static void onTextInput(boolean enabled, String program, int x, int y, int w, int h) {
        TextInputListener l = textInputListener;
        if (l != null) l.onTextInput(enabled, program != null ? program : "", x, y, w, h);
    }

    private static byte[] utf8(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    public static void setClipboardText(String text) {
        nativeSetClipboardText(utf8(text));
    }

    public static void textInputCommit(String text) {
        if (text != null && !text.isEmpty()) nativeTextInputCommit(utf8(text));
    }

    public static void textInputPreedit(String text, int cursor) {
        nativeTextInputPreedit(utf8(text), cursor, cursor);
    }

    public static void textInputDelete(int before, int after) {
        nativeTextInputDelete(before, after);
    }

    public static native int nativeCompositorTid();
    public static native void nativeStart(String xdgRuntimeDir);
    public static native void nativeStartWithSurface(
            Surface surface,
            String xdgRuntimeDir,
            String driverPath,
            String libraryName,
            String nativeLibDir
    );
    public static native void nativeSendPointer(int action, int x, int y);
    public static native void nativeSendTouch(int action, int pointerId, int x, int y);
    public static native int nativeCursorSnapshot(int[] out);
    public static native int nativeCursorSerial();
    public static native void nativeSetCursorComposite(boolean on);
    public static native void nativeSendKey(int evdev, int state);
    public static native boolean nativeFocusTakesTouch();
    public static native boolean nativeCursorInputPos(int[] out);
    public static native void nativeSendSceneInput(int type, int a, int b);
    public static native void nativeVsync(long frameTimeNanos);
    public static native void nativeSetZeroCopy(boolean on);
    public static native void nativeSetUbwc(boolean on);
    public static native void nativeSetLayerFrameRate(float hz);
    public static native void nativeSetOutputRefreshRate(float hz);
    public static native void nativeResetFirstFrame();
    public static native void nativeSetOutputSize(int width, int height);
    public static native void nativeSetDesktopScale(float scale);
    public static native void nativeResizeOutput(int width, int height);
    public static native void nativeSetScaleMode(int mode, int alignment);
    public static native boolean nativeScenePictureRect(float[] out);
    public static native void nativeSetRenderOnCommit(boolean on);
    public static native void nativeSetFpsLimit(int fps);
    public static native void nativeSetSurface(Surface surface);
    public static native boolean nativeWaitSurface(int timeoutMs);
    private static native void nativeSetClipboardText(byte[] utf8);
    private static native void nativeTextInputCommit(byte[] utf8);
    private static native void nativeTextInputPreedit(byte[] utf8, int cursorBegin, int cursorEnd);
    private static native void nativeTextInputDelete(int before, int after);
    public static native void nativeLog(String area, String message);

    private static float pendingDeltaX;
    private static float pendingDeltaY;

    public static synchronized void sendPointerDelta(float dx, float dy) {
        pendingDeltaX += dx * 256f;
        pendingDeltaY += dy * 256f;
        int x = (int) Math.floor(pendingDeltaX);
        int y = (int) Math.floor(pendingDeltaY);
        if (x == 0 && y == 0) return;
        pendingDeltaX -= x;
        pendingDeltaY -= y;
        nativeSendSceneInput(6, x, y);
    }

    public static void sendButton(int evdevButton, boolean pressed) {
        nativeSendSceneInput(3, evdevButton, pressed ? 1 : 0);
    }

    public static void sendWheelScroll(int vertical120, int horizontal120) {
        nativeSendSceneInput(11, vertical120, horizontal120);
    }

    public static void sendFingerScroll(int vertical120, int horizontal120) {
        nativeSendSceneInput(12, vertical120, horizontal120);
    }
}
