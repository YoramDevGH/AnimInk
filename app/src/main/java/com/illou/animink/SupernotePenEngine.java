package com.illou.animink;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;

/** Runtime bridge to the pen-write engine shipped in Supernote's boot class path. */
final class SupernotePenEngine {
    private static final String TAG = "AnimInkPen";
    interface Listener {
        void onStrokeFinished(Bitmap bitmap, Rect dirtyRect);
    }

    private final View host;
    private final Listener listener;
    private final Object controller;
    @SuppressWarnings("FieldCanBeLocal")
    private final Object drawEventProxy;
    private final Object pendingLock = new Object();
    private Rect pendingDirtyRect;
    private int pendingCallbacks;

    static SupernotePenEngine create(View host, Listener listener) {
        try {
            return new SupernotePenEngine(host, listener);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private SupernotePenEngine(View host, Listener listener) throws ReflectiveOperationException {
        this.host = host;
        this.listener = listener;
        Method getter = View.class.getMethod("getPWInterFace");
        controller = getter.invoke(host);
        if (controller == null) throw new IllegalStateException("Moteur PW indisponible");

        Class<?> callbackType = Class.forName("android.view.EinkPWInterface$PWDrawEventWithPoint");
        drawEventProxy = Proxy.newProxyInstance(host.getClass().getClassLoader(),
                new Class<?>[]{callbackType}, (proxy, method, args) -> {
                    if ("onTouchDrawEnd".equals(method.getName()) && args != null && args.length >= 2
                            && args[0] instanceof Bitmap && args[1] instanceof Rect) {
                        Bitmap bitmap = (Bitmap) args[0];
                        Rect dirty = new Rect((Rect) args[1]);
                        synchronized (pendingLock) {
                            if (pendingDirtyRect == null) pendingDirtyRect = new Rect(dirty);
                            else pendingDirtyRect.union(dirty);
                            pendingCallbacks++;
                        }
                        Log.d(TAG, "Native stroke finished: " + dirty);
                        host.post(() -> {
                            try {
                                listener.onStrokeFinished(bitmap, dirty);
                            } finally {
                                synchronized (pendingLock) {
                                    pendingCallbacks--;
                                    if (pendingCallbacks <= 0) {
                                        pendingCallbacks = 0;
                                        pendingDirtyRect = null;
                                    }
                                }
                            }
                        });
                    }
                    return null;
                });
        invoke("setDrawEventListener", new Class<?>[]{callbackType}, drawEventProxy);
        invoke("setDrawObjectPaintAntiAlias", new Class<?>[]{int.class}, 0);
        invoke("setOneWordDelayMs", new Class<?>[]{int.class}, 0);
        invoke("setPenColor", new Class<?>[]{int.class}, Color.BLACK);
        invoke("disablePenErase", new Class<?>[]{boolean.class}, false);
        // Keep stylus events available to the host View. Some Chauvet builds do
        // not activate the raw PW input route for sideloaded applications.
        invoke("enableTouchDispatch", new Class<?>[]{int.class}, 1);
        invoke("setPWEnabled", new Class<?>[]{boolean.class}, true);
    }

    void setTool(int tool, int color) {
        try {
            if (tool == InkCanvasView.TOOL_ERASER) {
                invoke("setPenStdEraseWidth", new Class<?>[]{float.class}, 42f);
                invoke("setDrawObjectType", new Class<?>[]{int.class}, 5);
            } else if (tool == InkCanvasView.TOOL_INK) {
                invoke("setPenType", new Class<?>[]{int.class}, 2);
                invoke("setPenSettingWidth", new Class<?>[]{int.class}, 4);
            } else {
                invoke("setPenType", new Class<?>[]{int.class}, 1);
                invoke("setPenSettingWidth", new Class<?>[]{int.class}, 2);
            }
            invoke("setPenColor", new Class<?>[]{int.class}, color);
        } catch (ReflectiveOperationException ignored) { }
    }

    void setEnabled(boolean enabled) {
        try { invoke("setPWEnabled", new Class<?>[]{boolean.class}, enabled); }
        catch (ReflectiveOperationException ignored) { }
    }

    void setBitmapVisible(boolean visible) {
        try {
            invoke("setPWBitmapInVisible", new Class<?>[]{boolean.class}, !visible);
            invoke("invalidateHost", new Class<?>[]{Rect.class}, (Object) null);
        } catch (ReflectiveOperationException ignored) { }
    }

    /** Presents an already-rendered page with the firmware's non-flashing UI waveform. */
    void refreshPageWithoutFlash(Rect rect) {
        try {
            invoke("invalidateHost", new Class<?>[]{Rect.class},
                    rect == null ? null : new Rect(rect));
        } catch (ReflectiveOperationException ignored) {
            if (rect == null) host.invalidate();
            else host.invalidate(rect);
        }
    }

    void setWritableRect(Rect rect) {
        try {
            invoke("rmAllWritableRects", new Class<?>[0]);
            invoke("addWritableRects", new Class<?>[]{java.util.List.class},
                    Collections.singletonList(new Rect(rect)));
        } catch (ReflectiveOperationException ignored) { }
    }

    void loadBitmap(Bitmap bitmap) {
        try {
            // A silent clear only repaints the host View. On Chauvet 3.24 the
            // PW backing bitmap can then retain pixels from the previous frame;
            // its first dirty update exposes those pixels around the new stroke.
            // Clear and present the whole native surface before installing the
            // next frame so both the visible e-ink page and PW's cache agree.
            invoke("clearContent", new Class<?>[]{Rect.class, boolean.class, boolean.class},
                    null, true, true);
            invoke("setPWBitmap", new Class<?>[]{Bitmap.class, Rect.class, Rect.class, boolean.class},
                    bitmap, null, null, true);
            invoke("invalidateHost", new Class<?>[]{Rect.class}, (Object) null);
        } catch (ReflectiveOperationException ignored) {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    /** Clears only the transient PW pixels and asks the host to reveal its raster below. */
    void clearTransient(Rect rect) {
        try {
            invoke("clearContent", new Class<?>[]{Rect.class, boolean.class, boolean.class},
                    rect == null ? null : new Rect(rect), true, true);
        } catch (ReflectiveOperationException ignored) { }
    }

    Bitmap getBitmap() {
        try { return (Bitmap) invoke("getPureWriteBitmap", new Class<?>[0]); }
        catch (ReflectiveOperationException ignored) { return null; }
    }

    boolean isReady() {
        try { return invoke("getPureWriteBitmap", new Class<?>[0]) != null; }
        catch (ReflectiveOperationException ignored) { return false; }
    }

    boolean isWriting() {
        try { return Boolean.TRUE.equals(invoke("isCurrentWriting", new Class<?>[0])); }
        catch (ReflectiveOperationException ignored) { return false; }
    }

    Rect getPendingDirtyRect() {
        synchronized (pendingLock) {
            return pendingDirtyRect == null ? null : new Rect(pendingDirtyRect);
        }
    }

    void forwardEvent(MotionEvent event) {
        try {
            invoke("sendBackEvent", new Class<?>[]{MotionEvent.class, boolean.class}, event, false);
        } catch (ReflectiveOperationException ignored) { }
    }

    void close() {
        try {
            Class<?> callbackType = Class.forName("android.view.EinkPWInterface$PWDrawEventWithPoint");
            invoke("setDrawEventListener", new Class<?>[]{callbackType}, (Object) null);
        } catch (ReflectiveOperationException ignored) { }
    }

    private Object invoke(String name, Class<?>[] types, Object... args)
            throws ReflectiveOperationException {
        try {
            Method method = controller.getClass().getMethod(name, types);
            return method.invoke(controller, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ReflectiveOperationException)
                throw (ReflectiveOperationException) cause;
            throw error;
        }
    }
}
