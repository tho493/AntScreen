package com.tho493.antscreen.engine;

import android.os.Build;
import android.util.Log;
import com.tho493.antscreen.hook.auto.AndroidAutoHook;
import com.tho493.antscreen.hook.system.SystemServerHook;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;

public class MirrorPayload {
    private static final String TAG = "AntScreen_Payload";

    public static void main(String[] args) {
        String proc = (args != null && args.length > 0) ? args[0] : "system_server";
        Log.i(TAG, "MirrorPayload daemon starting with proc=" + proc);
        init(proc);
        android.os.Looper.prepareMainLooper();
        android.os.Looper.loop();
    }

    public static void init() {
        init(null);
    }

    public static void init(String explicitProcessName) {
        String processName = (explicitProcessName != null && !explicitProcessName.isEmpty())
                ? explicitProcessName
                : getProcessName();

        Log.i(TAG, "AntScreen MirrorPayload.init() invoked in process: [" + processName + "]");

        if (processName == null || processName.isEmpty() || processName.contains("zygote")) {
            Log.w(TAG, "Process is still zygote / unspecialized, starting delayed resolver...");
            new Thread(() -> {
                for (int i = 0; i < 20; i++) {
                    try {
                        Thread.sleep(500);
                        String p = getProcessName();
                        if (p != null && !p.isEmpty() && !p.contains("zygote")) {
                            Log.i(TAG, "Delayed process detection resolved: " + p);
                            dispatch(p);
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }).start();
            return;
        }

        try {
            nativeLoadPine("/system/lib64/libpine.so");
        } catch (Throwable t) {
            Log.w(TAG, "nativeLoadPine failed: " + t.getMessage());
        }

        try {
            top.canyie.pine.PineConfig.libLoader = () -> {
                try {
                    nativeLoadPine("/system/lib64/libpine.so");
                } catch (Throwable t) {
                    System.loadLibrary("pine");
                }
            };
        } catch (Throwable ignored) {
        }

        dispatch(processName);
    }

    private static void dispatch(String processName) {
        if ("system_server".equals(processName)) {
            Log.i(TAG, "Dispatching to SystemServerHook...");
            try {
                SystemServerHook.initDirect();
            } catch (Throwable e) {
                Log.e(TAG, "Failed to invoke SystemServerHook.initDirect", e);
            }
        } else if (processName.contains("gearhead")) {
            Log.i(TAG, "Dispatching to AndroidAutoHook for " + processName + "...");
            try {
                AndroidAutoHook.initDirect(processName);
            } catch (Throwable e) {
                Log.e(TAG, "Failed to invoke AndroidAutoHook.initDirect", e);
            }
        } else {
            Log.d(TAG, "Process " + processName + " does not require hooks, skipping.");
        }
    }

    private static String getProcessName() {
        // 1. Try Application.getProcessName (API 28+)
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                String name = android.app.Application.getProcessName();
                if (name != null && !name.isEmpty()) return name;
            } catch (Throwable ignored) {
            }
        }

        // 2. Try ActivityThread.currentProcessName()
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method m = atClass.getMethod("currentProcessName");
            String name = (String) m.invoke(null);
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) {
        }

        // 3. Fallback: read /proc/self/cmdline
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
            String line = reader.readLine();
            if (line != null) {
                return line.trim().replace("\0", "");
            }
        } catch (Throwable ignored) {
        }

        return "";
    }

    public static void setConfigXml(String xml) {
        Log.d(TAG, "setConfigXml invoked");
    }

    public static void setLicenseXml(String xml) {
        Log.d(TAG, "setLicenseXml invoked");
    }

    public static void setDeliveredCoreEnc(byte[] enc) {
        Log.d(TAG, "setDeliveredCoreEnc invoked");
    }

    private static native boolean nativeLoadPine(String path);
}

