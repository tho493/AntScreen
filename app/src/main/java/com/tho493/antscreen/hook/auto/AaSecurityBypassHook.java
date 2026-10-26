package com.tho493.antscreen.hook.auto;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class AaSecurityBypassHook {
    private static final String TAG = "AntScreen_AaSecurity";
    private static final String TARGET_PKG = "com.tho493.antscreen";

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            hookInstallerSource(lpparam);
            hookSignatureVerification(lpparam);
        } catch (Throwable e) {
            Log.e(TAG, "AaSecurityBypassHook init failed", e);
        }
    }

    private static Context getGearheadContext(Object pm) {
        if (pm != null) {
            try {
                Object ctx = XposedHelpers.getObjectField(pm, "mContext");
                if (ctx instanceof Context) {
                    return (Context) ctx;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method cat = atClass.getMethod("currentApplication");
            Object app = cat.invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isTrustedOrInjected(Context ctx, String pkg) {
        if (pkg == null) return false;
        if (TARGET_PKG.equals(pkg)) return true;
        try {
            return com.tho493.antscreen.engine.AppManager.isPackageInjected(ctx, pkg);
        } catch (Throwable e) {
            return false;
        }
    }

    private static void hookInstallerSource(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> apmClass = lpparam.classLoader.loadClass("android.app.ApplicationPackageManager");

            // 1. Hook getInstallerPackageName to return Google Play Store
            XposedBridge.hookAllMethods(apmClass, "getInstallerPackageName", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof String) {
                        String pkg = (String) param.args[0];
                        Context ctx = getGearheadContext(param.thisObject);
                        if (isTrustedOrInjected(ctx, pkg)) {
                            param.setResult("com.android.vending");
                        }
                    }
                }
            });

            // 2. Hook getInstallSourceInfo (Android 11+)
            XposedBridge.hookAllMethods(apmClass, "getInstallSourceInfo", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof String) {
                        String pkg = (String) param.args[0];
                        Context ctx = getGearheadContext(param.thisObject);
                        if (isTrustedOrInjected(ctx, pkg)) {
                            Object info = param.getResult();
                            if (info != null) {
                                try {
                                    for (java.lang.reflect.Field f : info.getClass().getDeclaredFields()) {
                                        if (f.getType() == String.class) {
                                            f.setAccessible(true);
                                            String val = (String) f.get(info);
                                            if (val != null && !val.equals("com.android.vending")) {
                                                f.set(info, "com.android.vending");
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }
            });

            Log.i(TAG, "InstallerPackageName & InstallSourceInfo bypass installed for AntScreen and injected apps");
        } catch (Throwable e) {
            Log.w(TAG, "Installer source hook fallback: " + e.getMessage());
        }
    }

    private static void hookSignatureVerification(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Bypass PackageInfo signature check inside Gearhead
            XposedBridge.hookAllMethods(lpparam.classLoader.loadClass("android.app.ApplicationPackageManager"),
                    "getPackageInfo", new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            String pkg = (String) param.args[0];
                            Context ctx = getGearheadContext(param.thisObject);
                            if (isTrustedOrInjected(ctx, pkg)) {
                                PackageInfo pi = (PackageInfo) param.getResult();
                                if (pi != null && pi.applicationInfo != null) {
                                    // Ensure flags indicate trusted app
                                    pi.applicationInfo.flags |= 1; // FLAG_SYSTEM
                                }
                            }
                        }
                    });
            Log.i(TAG, "Signature/PackageInfo check bypass installed for AntScreen and injected apps");
        } catch (Throwable e) {
            Log.w(TAG, "Signature verification hook fallback: " + e.getMessage());
        }
    }
}
