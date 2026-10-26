package com.tho493.antscreen.hook;

import android.util.Log;

import com.tho493.antscreen.hook.auto.AndroidAutoHook;
import com.tho493.antscreen.hook.system.SystemServerHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage {
    private static final String TAG = "AntScreen_Xposed";
    private static final String PKG_SYSTEM_SERVER = "android";
    private static final String PKG_GEARHEAD = "com.google.android.projection.gearhead";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }

        if (PKG_SYSTEM_SERVER.equals(lpparam.packageName) && "android".equals(lpparam.processName)) {
            Log.i(TAG, "Initializing SystemServerHook for system_server");
            SystemServerHook.init(lpparam);
        } else if (PKG_GEARHEAD.equals(lpparam.packageName)) {
            Log.i(TAG, "Initializing AndroidAutoHook for " + lpparam.processName);
            AndroidAutoHook.init(lpparam);
        }
    }
}
