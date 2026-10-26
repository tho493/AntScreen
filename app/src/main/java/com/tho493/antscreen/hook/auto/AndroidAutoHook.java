package com.tho493.antscreen.hook.auto;

import android.app.Application;
import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class AndroidAutoHook {
    private static final String TAG = "AntScreen_AutoHook";
    private static volatile boolean sInitialized = false;

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Log.i(TAG, "Initializing AndroidAutoHook components for " + lpparam.processName);
            AaSecurityBypassHook.init(lpparam);
            AaCoolwalkUiHook.init(lpparam);
            AaAppLauncherHook.init(lpparam);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to initialize AndroidAutoHook", e);
        }
    }

    public static void initDirect(String processName) {
        if (sInitialized) return;
        Log.i(TAG, "Initializing AndroidAutoHook directly in " + processName);

        // 1. Thử lấy Application ngay lập tức nếu đã tồn tại
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method getAppMethod = atClass.getMethod("currentApplication");
            Application app = (Application) getAppMethod.invoke(null);
            if (app != null) {
                dispatchInit(app, processName, "currentApplication_immediate");
                return;
            }
        } catch (Throwable ignored) {
        }

        // 2. Hook Instrumentation.callApplicationOnCreate bằng Pine để đón đầu NGAY KHI Application vừa tạo
        try {
            Class<?> instrCls = Class.forName("android.app.Instrumentation");
            Method callOnCreateMethod = null;
            for (Method m : instrCls.getDeclaredMethods()) {
                if (m.getName().equals("callApplicationOnCreate") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == Application.class) {
                    callOnCreateMethod = m;
                    break;
                }
            }
            if (callOnCreateMethod != null) {
                callOnCreateMethod.setAccessible(true);
                top.canyie.pine.Pine.hook(callOnCreateMethod, new top.canyie.pine.callback.MethodHook() {
                    @Override
                    public void beforeCall(top.canyie.pine.Pine.CallFrame callFrame) {
                        if (callFrame.args != null && callFrame.args.length > 0 && callFrame.args[0] instanceof Application) {
                            Application app = (Application) callFrame.args[0];
                            dispatchInit(app, processName, "callApplicationOnCreate");
                        }
                    }
                });
                Log.i(TAG, "Hooked Instrumentation.callApplicationOnCreate for instant Gearhead capture");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Pine hook callApplicationOnCreate failed: " + t.getMessage());
        }

        // 3. Watchdog fallback kiểm tra nhanh (mỗi 100ms)
        new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                if (sInitialized) break;
                try {
                    Thread.sleep(100);
                    Class<?> atClass = Class.forName("android.app.ActivityThread");
                    Method curAtMethod = atClass.getMethod("currentActivityThread");
                    Object at = curAtMethod.invoke(null);
                    if (at != null) {
                        Method getAppMethod = atClass.getMethod("currentApplication");
                        Application app = (Application) getAppMethod.invoke(at);
                        if (app != null) {
                            dispatchInit(app, processName, "watchdog_loop");
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "AntScreen_HookWatchdog").start();
    }

    private static synchronized void dispatchInit(Application app, String processName, String source) {
        if (sInitialized) return;
        sInitialized = true;
        ClassLoader cl = app.getClassLoader();
        Log.i(TAG, "SUCCESS: Captured Gearhead application via " + source + ", cl=" + cl);
        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(new de.robv.android.xposed.XposedBridge.CopyOnWriteSortedSet<>());
        lpparam.packageName = "com.google.android.projection.gearhead";
        lpparam.processName = processName;
        lpparam.classLoader = cl;
        lpparam.appInfo = app.getApplicationInfo();
        init(lpparam);
    }
}

