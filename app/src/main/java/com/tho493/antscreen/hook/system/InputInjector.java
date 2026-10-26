package com.tho493.antscreen.hook.system;

import android.os.SystemClock;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

public class InputInjector {
    private static final String TAG = "AntScreen_InputInjector";
    private static Method sInjectInputEventMethod;
    private static Object sInputManagerInstance;

    static {
        try {
            Class<?> inputManagerClass;
            try {
                // Android 14+ InputManagerGlobal
                inputManagerClass = Class.forName("android.hardware.input.InputManagerGlobal");
                Method getInstanceMethod = inputManagerClass.getMethod("getInstance");
                sInputManagerInstance = getInstanceMethod.invoke(null);
            } catch (Throwable ignored) {
                // Android 12 - 13 InputManager
                inputManagerClass = Class.forName("android.hardware.input.InputManager");
                Method getInstanceMethod = inputManagerClass.getMethod("getInstance");
                sInputManagerInstance = getInstanceMethod.invoke(null);
            }

            sInjectInputEventMethod = inputManagerClass.getMethod("injectInputEvent", InputEvent.class, int.class);
            sInjectInputEventMethod.setAccessible(true);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to initialize injectInputEvent reflection", e);
        }
    }

    public static boolean injectInputEvent(InputEvent event, int mode) {
        if (sInjectInputEventMethod == null || sInputManagerInstance == null) {
            Log.e(TAG, "InputManager injection not available");
            return false;
        }
        try {
            return (Boolean) sInjectInputEventMethod.invoke(sInputManagerInstance, event, mode);
        } catch (Throwable e) {
            Log.e(TAG, "injectInputEvent failed", e);
            return false;
        }
    }

    public static void injectTouch(int action, float x, float y, int displayId) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = 1.0f;
        coords[0].size = 1.0f;

        MotionEvent event = MotionEvent.obtain(
                now, now, action, 1, properties, coords,
                0, 0, 1.0f, 1.0f, 0, 0, 0x1002, 0 // InputDevice.SOURCE_TOUCHSCREEN
        );

        if (displayId > 0) {
            try {
                Method setDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", int.class);
                setDisplayIdMethod.invoke(event, displayId);
            } catch (Throwable ignored) {
            }
        }

        injectInputEvent(event, 0); // INJECT_INPUT_EVENT_MODE_ASYNC
        event.recycle();
    }

    public static void injectKey(int keyCode, int displayId) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        down.setSource(0x101); // InputDevice.SOURCE_KEYBOARD
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
        up.setSource(0x101);

        if (displayId > 0) {
            try {
                Method setDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", int.class);
                setDisplayIdMethod.invoke(down, displayId);
                setDisplayIdMethod.invoke(up, displayId);
            } catch (Throwable ignored) {
            }
        }

        injectInputEvent(down, 0);
        injectInputEvent(up, 0);
    }
}
