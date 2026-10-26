package com.tho493.antscreen.hook.auto;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import com.tho493.antscreen.engine.AppManager;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class AaAppLauncherHook {
    private static final String TAG = "AntScreen_LauncherHook";
    private static final String SLOT_PREFIX = "InjectedAppServices$Slot_";

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            hookPackageManager(lpparam);
            hookPackageItemInfo(lpparam);
            hookResolveInfo(lpparam);
            hookAppIconFactory(lpparam);
            Log.i(TAG, "AaAppLauncherHook initialized in " + lpparam.processName);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to initialize AaAppLauncherHook", e);
        }
    }

    private static int parseSlot(String className) {
        if (className == null) return -1;
        int idx = className.indexOf(SLOT_PREFIX);
        if (idx != -1) {
            try {
                return Integer.parseInt(className.substring(idx + SLOT_PREFIX.length()));
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static volatile Context sCachedContext = null;

    private static Context getGearheadContext(Object pm) {
        if (sCachedContext != null) return sCachedContext;
        if (pm != null) {
            try {
                Object ctx = XposedHelpers.getObjectField(pm, "mContext");
                if (ctx instanceof Context) {
                    sCachedContext = (Context) ctx;
                    return sCachedContext;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method cat = atClass.getMethod("currentApplication");
            Object app = cat.invoke(null);
            if (app instanceof Context) {
                sCachedContext = (Context) app;
                return sCachedContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final java.util.Map<String, Drawable> sIconCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static Drawable getTargetAppIcon(PackageManager pm, Context ctx, String targetPkg) {
        if (targetPkg == null || targetPkg.isEmpty() || pm == null) return null;
        Drawable cached = sIconCache.get(targetPkg);
        if (cached != null) return cached;
        try {
            Drawable orig = pm.getApplicationIcon(targetPkg);
            if (orig == null) return null;

            if (orig instanceof BitmapDrawable) {
                sIconCache.put(targetPkg, orig);
                return orig;
            }

            int w = orig.getIntrinsicWidth() > 0 ? orig.getIntrinsicWidth() : 144;
            int h = orig.getIntrinsicHeight() > 0 ? orig.getIntrinsicHeight() : 144;
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            orig.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            orig.draw(canvas);

            BitmapDrawable bd = new BitmapDrawable(ctx != null ? ctx.getResources() : null, bitmap);
            sIconCache.put(targetPkg, bd);
            return bd;
        } catch (Throwable t) {
            Log.w(TAG, "Error generating flattened icon for " + targetPkg + ": " + t.getMessage());
            return null;
        }
    }

    private static boolean isInjectedTarget(Context ctx, String packageName) {
        if (packageName == null) return false;
        return AppManager.isPackageInjected(ctx, packageName);
    }

    private static void hookPackageManager(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> apmClass = Class.forName("android.app.ApplicationPackageManager");

            XposedBridge.hookAllMethods(apmClass, "getApplicationIcon", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length != 1 || param.args[0] == null) return;
                    PackageManager pm = (PackageManager) param.thisObject;
                    Context ctx = getGearheadContext(pm);
                    String pkg = null;
                    if (param.args[0] instanceof ApplicationInfo) {
                        pkg = ((ApplicationInfo) param.args[0]).packageName;
                    } else if (param.args[0] instanceof String) {
                        pkg = (String) param.args[0];
                    }
                    if (pkg != null && isInjectedTarget(ctx, pkg)) {
                        Drawable icon = getTargetAppIcon(pm, ctx, pkg);
                        if (icon != null) {
                            param.setResult(icon);
                            Log.d(TAG, "getApplicationIcon replaced for " + pkg);
                        }
                    }
                }
            });

            XposedBridge.hookAllMethods(ApplicationInfo.class, "loadIcon", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    ApplicationInfo ai = (ApplicationInfo) param.thisObject;
                    if (param.args.length > 0 && param.args[0] instanceof PackageManager) {
                        PackageManager pm = (PackageManager) param.args[0];
                        Context ctx = getGearheadContext(pm);
                        if (ai != null && isInjectedTarget(ctx, ai.packageName)) {
                            Drawable icon = getTargetAppIcon(pm, ctx, ai.packageName);
                            if (icon != null) {
                                param.setResult(icon);
                                Log.d(TAG, "ApplicationInfo.loadIcon replaced for " + ai.packageName);
                            }
                        }
                    }
                }
            });

            Class<?> componentInfoClass = Class.forName("android.content.pm.ComponentInfo");
            XposedBridge.hookAllMethods(componentInfoClass, "getIconResource", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    ComponentInfo info = (ComponentInfo) param.thisObject;
                    if (parseSlot(info.name) != -1) {
                        param.setResult(0);
                        Log.d(TAG, "Forced injected ComponentInfo icon resource to application fallback: " + info.name);
                    }
                }
            });

            // 0. Hook queryIntentServices & queryIntentServicesAsUser to decorate ResolveInfo with target app icons & labels
            XC_MethodHook queryServicesHook = new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    List<ResolveInfo> list = (List<ResolveInfo>) param.getResult();
                    if (list == null || list.isEmpty()) return;
                    PackageManager pm = (PackageManager) param.thisObject;
                    Context ctx = getGearheadContext(pm);
                    boolean isFullscreen = com.tho493.antscreen.engine.SettingsManager.isFullscreenMode(ctx);

                    Intent queryIntent = (param.args.length > 0 && param.args[0] instanceof Intent)
                            ? (Intent) param.args[0] : null;
                    boolean isNavQuery = queryIntent != null && queryIntent.hasCategory("com.google.android.gms.car.category.CATEGORY_PROJECTION_NAVIGATION");

                    // Nếu tắt Fullscreen và Gearhead đang tìm các Navigation App -> Loại bỏ AntScreen khỏi danh sách Navigation
                    if (!isFullscreen && isNavQuery) {
                        java.util.Iterator<ResolveInfo> it = list.iterator();
                        while (it.hasNext()) {
                            ResolveInfo ri = it.next();
                            if (isAntScreenService(ri)) {
                                it.remove();
                            }
                        }
                        return;
                    }

                    List<String> injectedList = AppManager.getInjectedPackagesList(ctx);
                    for (ResolveInfo ri : list) {
                        if (ri == null || ri.serviceInfo == null) continue;
                        if (isAntScreenService(ri)) {
                            updateFilterCategory(ri.filter, isFullscreen);
                        }

                        int slot = parseSlot(ri.serviceInfo.name);
                        if (slot >= 0 && slot < injectedList.size()) {
                            String targetPkg = injectedList.get(slot);
                            if (targetPkg != null) {
                                try {
                                    ApplicationInfo targetAi = pm.getApplicationInfo(targetPkg, 0);
                                    CharSequence label = pm.getApplicationLabel(targetAi);
                                    ApplicationInfo iconAi = new ApplicationInfo(targetAi);
                                    iconAi.icon = 0;
                                    ri.nonLocalizedLabel = label;
                                    ri.serviceInfo.nonLocalizedLabel = label;
                                    ri.icon = 0;
                                    ri.serviceInfo.icon = 0;
                                    ri.serviceInfo.applicationInfo = iconAi;
                                    ri.resolvePackageName = targetPkg;
                                    Log.d(TAG, "Decorated ResolveInfo slot " + slot + " -> " + targetPkg + " (" + label + ") [fullscreen=" + isFullscreen + "]");
                                } catch (Throwable e) {
                                    Log.w(TAG, "Failed decorating ResolveInfo for slot " + slot + ": " + e.getMessage());
                                }
                            }
                        }
                    }
                }
            };
            XposedBridge.hookAllMethods(apmClass, "queryIntentServices", queryServicesHook);
            XposedBridge.hookAllMethods(apmClass, "queryIntentServicesAsUser", queryServicesHook);

            // 1. Hook loadItemIcon
            XposedBridge.hookAllMethods(apmClass, "loadItemIcon", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof PackageItemInfo) {
                        PackageItemInfo item = (PackageItemInfo) param.args[0];
                        int slot = parseSlot(item.name);
                        if (slot != -1) {
                            PackageManager pm = (PackageManager) param.thisObject;
                            Context ctx = getGearheadContext(pm);
                            String targetPkg = AppManager.getPackageForSlot(ctx, slot);
                            if (targetPkg != null) {
                                Drawable icon = getTargetAppIcon(pm, ctx, targetPkg);
                                if (icon != null) {
                                    param.setResult(icon);
                                    Log.d(TAG, "loadItemIcon replaced for slot " + slot + " -> " + targetPkg);
                                }
                            }
                        }
                    }
                }
            });

            // 2. Hook loadUnbadgedItemIcon
            XposedBridge.hookAllMethods(apmClass, "loadUnbadgedItemIcon", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof PackageItemInfo) {
                        PackageItemInfo item = (PackageItemInfo) param.args[0];
                        int slot = parseSlot(item.name);
                        if (slot != -1) {
                            PackageManager pm = (PackageManager) param.thisObject;
                            Context ctx = getGearheadContext(pm);
                            String targetPkg = AppManager.getPackageForSlot(ctx, slot);
                            if (targetPkg != null) {
                                Drawable icon = getTargetAppIcon(pm, ctx, targetPkg);
                                if (icon != null) {
                                    param.setResult(icon);
                                    Log.d(TAG, "loadUnbadgedItemIcon replaced for slot " + slot + " -> " + targetPkg);
                                }
                            }
                        }
                    }
                }
            });

            // 3. Hook getServiceIcon & getActivityIcon
            XC_MethodHook componentIconHook = new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof android.content.ComponentName) {
                        android.content.ComponentName cn = (android.content.ComponentName) param.args[0];
                        int slot = parseSlot(cn.getClassName());
                        if (slot != -1) {
                            PackageManager pm = (PackageManager) param.thisObject;
                            Context ctx = getGearheadContext(pm);
                            String targetPkg = AppManager.getPackageForSlot(ctx, slot);
                            if (targetPkg != null) {
                                Drawable icon = getTargetAppIcon(pm, ctx, targetPkg);
                                if (icon != null) {
                                    param.setResult(icon);
                                    Log.d(TAG, "Component icon hooked for slot " + slot + " -> " + targetPkg);
                                }
                            }
                        }
                    }
                }
            };
            XposedBridge.hookAllMethods(apmClass, "getServiceIcon", componentIconHook);
            XposedBridge.hookAllMethods(apmClass, "getActivityIcon", componentIconHook);

            // 4. Hook getServiceInfo to set nonLocalizedLabel & icon
            XposedBridge.hookAllMethods(apmClass, "getServiceInfo", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    ServiceInfo si = (ServiceInfo) param.getResult();
                    if (si != null) {
                        int slot = parseSlot(si.name);
                        if (slot != -1) {
                            PackageManager pm = (PackageManager) param.thisObject;
                            Context ctx = getGearheadContext(pm);
                            String targetPkg = AppManager.getPackageForSlot(ctx, slot);
                            if (targetPkg != null) {
                                try {
                                    ApplicationInfo ai = pm.getApplicationInfo(targetPkg, 0);
                                    CharSequence label = pm.getApplicationLabel(ai);
                                    ai = new ApplicationInfo(ai);
                                    ai.icon = 0;
                                    si.nonLocalizedLabel = label;
                                    si.applicationInfo = ai;
                                    si.icon = 0;
                                    param.setResult(si);
                                    Log.d(TAG, "getServiceInfo decorated for slot " + slot + " -> " + targetPkg);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }
            });

        } catch (Throwable e) {
            Log.w(TAG, "hookPackageManager error: " + e.getMessage());
        }
    }

    private static final java.util.Set<Class<?>> sFactoryHooked = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static void hookAppIconFactory(XC_LoadPackage.LoadPackageParam lpparam) {
        String[] factoryClasses = {"lfz", "defpackage.lfz", "com.google.android.apps.auto.components.graphics.icon.AppIconFactory"};
        for (String clsName : factoryClasses) {
            try {
                Class<?> clazz = lpparam.classLoader.loadClass(clsName);
                if (clazz != null) {
                    hookAppIconFactory(clazz);
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookAppIconFactory(Class<?> factoryClass) {
        if (factoryClass == null || !sFactoryHooked.add(factoryClass)) return;
        try {
            XposedBridge.hookAllMethods(factoryClass, "a", new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 6) return;
                        Object lastArg = param.args[param.args.length - 1];
                        if (!(lastArg instanceof ComponentName)) return;

                        ComponentName cn = (ComponentName) lastArg;
                        int slot = parseSlot(cn.getClassName());
                        if (slot == -1) return;

                        Context ctx = getGearheadContext(null);
                        if (ctx == null) return;

                        String targetPkg = AppManager.getPackageForSlot(ctx, slot);
                        if (targetPkg == null) return;

                        PackageManager pm = ctx.getPackageManager();
                        Drawable icon = getTargetAppIcon(pm, ctx, targetPkg);
                        if (icon != null) {
                            param.args[0] = icon;
                            Log.i(TAG, "SUCCESS: AppIconFactory replaced drawable for slot " + slot + " -> " + targetPkg);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Error in AppIconFactory hook: " + t.getMessage());
                    }
                }
            });
            Log.i(TAG, "Successfully hooked AppIconFactory: " + factoryClass.getName());
        } catch (Throwable t) {
            Log.w(TAG, "Failed hooking AppIconFactory " + factoryClass.getName() + ": " + t.getMessage());
        }
    }

    private static void hookPackageItemInfo(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> piiClass = lpparam.classLoader.loadClass("android.content.pm.PackageItemInfo");

            // 1. Hook loadLabel
            XposedBridge.hookAllMethods(piiClass, "loadLabel", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    PackageItemInfo item = (PackageItemInfo) param.thisObject;
                    int slot = parseSlot(item.name);
                    if (slot != -1) {
                        PackageManager pm = (PackageManager) param.args[0];
                        Context ctx = getGearheadContext(pm);
                        String targetPkg = AppManager.getPackageForSlot(ctx, slot);
                        if (targetPkg != null) {
                            try {
                                ApplicationInfo ai = pm.getApplicationInfo(targetPkg, 0);
                                CharSequence label = pm.getApplicationLabel(ai);
                                if (label != null) {
                                    param.setResult(label);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            });

            // 2. Hook loadIcon
            XposedBridge.hookAllMethods(piiClass, "loadIcon", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    PackageItemInfo item = (PackageItemInfo) param.thisObject;
                    int slot = parseSlot(item.name);
                    if (slot != -1) {
                        PackageManager pm = (PackageManager) param.args[0];
                        Context ctx = getGearheadContext(pm);
                        String targetPkg = AppManager.getPackageForSlot(ctx, slot);
                        if (targetPkg != null) {
                            Drawable icon = getTargetAppIcon(pm, ctx, targetPkg);
                            if (icon != null) {
                                param.setResult(icon);
                            }
                        }
                    }
                }
            });

        } catch (Throwable e) {
            Log.w(TAG, "hookPackageItemInfo error: " + e.getMessage());
        }
    }

    private static void hookResolveInfo(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> riClass = lpparam.classLoader.loadClass("android.content.pm.ResolveInfo");

            // 1. Hook loadLabel
            XposedBridge.hookAllMethods(riClass, "loadLabel", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    ResolveInfo ri = (ResolveInfo) param.thisObject;
                    String sName = (ri.serviceInfo != null) ? ri.serviceInfo.name : null;
                    int slot = parseSlot(sName);
                    if (slot != -1) {
                        PackageManager pm = (PackageManager) param.args[0];
                        Context ctx = getGearheadContext(pm);
                        String targetPkg = AppManager.getPackageForSlot(ctx, slot);
                        if (targetPkg != null) {
                            try {
                                ApplicationInfo ai = pm.getApplicationInfo(targetPkg, 0);
                                CharSequence label = pm.getApplicationLabel(ai);
                                if (label != null) {
                                    param.setResult(label);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            });

            // 2. Hook loadIcon
            XposedBridge.hookAllMethods(riClass, "loadIcon", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    ResolveInfo ri = (ResolveInfo) param.thisObject;
                    String sName = (ri.serviceInfo != null) ? ri.serviceInfo.name : null;
                    int slot = parseSlot(sName);
                    if (slot != -1) {
                        PackageManager pm = (PackageManager) param.args[0];
                        Context ctx = getGearheadContext(pm);
                        String targetPkg = AppManager.getPackageForSlot(ctx, slot);
                        if (targetPkg != null) {
                            Drawable icon = getTargetAppIcon(pm, ctx, targetPkg);
                            if (icon != null) {
                                param.setResult(icon);
                            }
                        }
                    }
                }
            });
            Log.i(TAG, "hookResolveInfo registered successfully");
        } catch (Throwable e) {
            Log.w(TAG, "hookResolveInfo error: " + e.getMessage());
        }
    }

    private static void updateFilterCategory(android.content.IntentFilter filter, boolean fullscreen) {
        if (filter == null) return;
        String navCat = "com.google.android.gms.car.category.CATEGORY_PROJECTION_NAVIGATION";
        if (fullscreen) {
            if (!filter.hasCategory(navCat)) {
                filter.addCategory(navCat);
            }
        } else {
            try {
                java.lang.reflect.Field catField = android.content.IntentFilter.class.getDeclaredField("mCategories");
                catField.setAccessible(true);
                java.util.ArrayList<String> cats = (java.util.ArrayList<String>) catField.get(filter);
                if (cats != null) {
                    cats.remove(navCat);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isAntScreenService(ResolveInfo ri) {
        if (ri == null || ri.serviceInfo == null) return false;
        String pkg = ri.serviceInfo.packageName;
        if ("com.tho493.antscreen".equals(pkg)) return true;
        String name = ri.serviceInfo.name;
        if (name != null && (name.contains("AaActivityService") || name.contains("InjectedAppServices") || name.contains("antscreen"))) {
            return true;
        }
        return false;
    }
}
