package com.tho493.antscreen.hook.system;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import com.tho493.antscreen.engine.AppManager;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SystemServerHook {
    private static final String TAG = "AntScreen_SysHook";
    private static MirrorManagerService sService;

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            hookServiceManagerPublish(lpparam);
            hookVirtualDisplayCreation(lpparam);
        } catch (Throwable e) {
            Log.e(TAG, "SystemServerHook init failed", e);
        }
    }

    public static void initDirect() {
        Log.i(TAG, "Initializing SystemServerHook directly via Zygisk native injection!");
        new Thread(() -> {
            for (int i = 0; i < 45; i++) {
                try {
                    Thread.sleep(1000);
                    Class<?> atClass = Class.forName("android.app.ActivityThread");
                    Method catMethod = atClass.getMethod("currentActivityThread");
                    Object at = catMethod.invoke(null);
                    if (at != null) {
                        Method getSysContextMethod = atClass.getMethod("getSystemContext");
                        Context sysContext = (Context) getSysContextMethod.invoke(at);
                        if (sysContext != null) {
                            if (sService == null) {
                                sService = new MirrorManagerService(sysContext);
                                publishBinderService("antscreen_mirror", sService);
                                registerBinderHandshake(sysContext, sService);
                                notifyClients(sysContext, sService);
                                updateInjectedAppLabels(sysContext);

                                ClassLoader sysClassLoader = sysContext.getClassLoader();
                                hookTaskSupervisorLaunch(sysClassLoader);
                                hookDisplayGroupMapper(sysClassLoader);

                                Log.i(TAG, "SUCCESS: MirrorManagerService published, hooks armed, and handshake registered!");
                            }
                            break;
                        }
                    }
                } catch (Throwable e) {
                    Log.d(TAG, "Waiting for system context: " + e.getMessage());
                }
            }
        }).start();
    }

    private static void hookTaskSupervisorLaunch(ClassLoader sysClassLoader) {
        try {
            Class<?> atsClass = Class.forName("com.android.server.wm.ActivityTaskSupervisor", false, sysClassLoader);
            for (Method m : atsClass.getDeclaredMethods()) {
                if (m.getName().equals("isCallerAllowedToLaunchOnDisplay") && m.getParameterCount() == 4) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            if (sService != null && param.args.length >= 3 && param.args[2] instanceof Integer) {
                                int displayId = (Integer) param.args[2];
                                if (displayId != 0 && displayId == sService.getCurrentDisplayId()) {
                                    param.setResult(true);
                                    Log.d(TAG, "Bypassed isCallerAllowedToLaunchOnDisplay for displayId: " + displayId);
                                }
                            }
                        }
                    });
                    Log.i(TAG, "Hooked isCallerAllowedToLaunchOnDisplay successfully");
                    break;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to hook isCallerAllowedToLaunchOnDisplay: " + e.getMessage());
        }
    }

    private static void hookDisplayGroupMapper(ClassLoader sysClassLoader) {
        try {
            Class<?> ldmClass = Class.forName("com.android.server.display.LogicalDisplayMapper", false, sysClassLoader);
            Method updateMethod = null;
            for (Method m : ldmClass.getDeclaredMethods()) {
                if (m.getName().equals("updateLogicalDisplaysLocked")) {
                    if (updateMethod == null || m.getParameterCount() > updateMethod.getParameterCount()) {
                        updateMethod = m;
                    }
                }
            }
            if (updateMethod != null) {
                XposedBridge.hookMethod(updateMethod, new XC_MethodHook() {
                    @Override
                    public void beforeHookedMethod(MethodHookParam param) {
                        try {
                            java.lang.reflect.Field fDisplays = ldmClass.getDeclaredField("mLogicalDisplays");
                            fDisplays.setAccessible(true);
                            android.util.SparseArray<?> displays = (android.util.SparseArray<?>) fDisplays.get(param.thisObject);
                            if (displays != null) {
                                for (int i = 0; i < displays.size(); i++) {
                                    Object ld = displays.valueAt(i);
                                    if (ld != null) {
                                        java.lang.reflect.Field fPrimary = ld.getClass().getDeclaredField("mPrimaryDisplayDevice");
                                        fPrimary.setAccessible(true);
                                        Object dev = fPrimary.get(ld);
                                        if (dev != null) {
                                            Method getInfo = dev.getClass().getMethod("getDisplayDeviceInfoLocked");
                                            getInfo.setAccessible(true);
                                            Object info = getInfo.invoke(dev);
                                            if (info != null) {
                                                java.lang.reflect.Field fName = info.getClass().getDeclaredField("name");
                                                fName.setAccessible(true);
                                                String dName = (String) fName.get(info);
                                                if (dName != null && dName.contains("AntScreen")) {
                                                    java.lang.reflect.Field fGroup = ld.getClass().getDeclaredField("mDisplayGroupName");
                                                    fGroup.setAccessible(true);
                                                    fGroup.set(ld, "AntScreen_DisplayGroup");
                                                    Log.d(TAG, "Assigned displayGroup 'AntScreen_DisplayGroup' to " + dName);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                Log.i(TAG, "Hooked updateLogicalDisplaysLocked for independent power group");
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to hook LogicalDisplayMapper: " + e.getMessage());
        }
    }


    private static void hookServiceManagerPublish(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> amsClass = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", lpparam.classLoader);
            XposedBridge.hookAllMethods(amsClass, "systemReady", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    if (context != null && sService == null) {
                        sService = new MirrorManagerService(context);
                        publishBinderService("antscreen_mirror", sService);
                        registerBinderHandshake(context, sService);
                        notifyClients(context, sService);
                        Log.i(TAG, "MirrorManagerService published & binder handshake registered via Xposed hook");
                    }
                }
            });
        } catch (Throwable e) {
            Log.e(TAG, "Failed to hook systemReady for ServiceManager publishing", e);
        }
    }

    private static void registerBinderHandshake(Context sysContext, MirrorManagerService service) {
        try {
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, android.content.Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if ("com.tho493.antscreen.action.REQUEST_BINDER".equals(action)
                            || "com.tho493.antscreen.action.SYNC_APPS".equals(action)) {
                        String targetPkg = intent.getStringExtra("target_package");
                        Log.i(TAG, "Received " + action + ", updating injected app labels in system_server");
                        updateInjectedAppLabels(sysContext);
                        if ("com.tho493.antscreen.action.REQUEST_BINDER".equals(action)) {
                            if (targetPkg != null && !targetPkg.isEmpty()) {
                                sendBinderToPackage(sysContext, targetPkg, service);
                            } else {
                                notifyClients(sysContext, service);
                            }
                        }
                    }
                }
            };

            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction("com.tho493.antscreen.action.REQUEST_BINDER");
            filter.addAction("com.tho493.antscreen.action.SYNC_APPS");
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                sysContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                sysContext.registerReceiver(receiver, filter);
            }
            Log.i(TAG, "Binder handshake BroadcastReceiver registered successfully");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to register binder handshake receiver", e);
        }
    }

    private static void notifyClients(Context sysContext, MirrorManagerService service) {
        sendBinderToPackage(sysContext, "com.tho493.antscreen", service);
        sendBinderToPackage(sysContext, "com.google.android.projection.gearhead", service);
    }

    private static void sendBinderToPackage(Context sysContext, String pkg, MirrorManagerService service) {
        try {
            android.content.Intent response = new android.content.Intent("com.tho493.antscreen.action.BINDER_RESPONSE");
            response.setPackage(pkg);
            android.os.Bundle bundle = new android.os.Bundle();
            bundle.putBinder("binder", service);
            response.putExtra("extra_ipc", bundle);

            Class<?> uhClass = Class.forName("android.os.UserHandle");
            Object userAll = uhClass.getField("ALL").get(null);
            Method sendBroadcastAsUser = sysContext.getClass().getMethod("sendBroadcastAsUser", android.content.Intent.class, (Class<?>) uhClass);
            sendBroadcastAsUser.invoke(sysContext, response, userAll);
            Log.d(TAG, "Dispatched BINDER_RESPONSE with direct Binder to " + pkg);
        } catch (Throwable e) {
            Log.w(TAG, "sendBinderToPackage failed for " + pkg + ": " + e.getMessage());
        }
    }

    private static void publishBinderService(String name, IBinder service) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method addService = smClass.getMethod("addService", String.class, IBinder.class, boolean.class);
            addService.invoke(null, name, service, true);
        } catch (Throwable e) {
            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                Method addService = smClass.getMethod("addService", String.class, IBinder.class);
                addService.invoke(null, name, service);
            } catch (Throwable ex) {
                Log.e(TAG, "Failed to call ServiceManager.addService", ex);
            }
        }
    }

    private static void hookVirtualDisplayCreation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> dmsClass = XposedHelpers.findClass("com.android.server.display.DisplayManagerService", lpparam.classLoader);
            // Catch virtual display creation to record displayId
            XposedBridge.hookAllMethods(dmsClass, "createVirtualDisplayInternal", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() instanceof Integer) {
                        int displayId = (Integer) param.getResult();
                        Object callback = param.args != null && param.args.length > 0 ? param.args[0] : null;
                        String name = param.args != null && param.args.length > 2 && param.args[2] instanceof String ? (String) param.args[2] : "";

                        if (name.contains("AntScreen") || name.contains("KmDisplay")) {
                            Log.i(TAG, "Virtual display created: " + name + " -> ID: " + displayId);
                            if (sService != null) {
                                sService.setCurrentDisplayId(displayId);
                            }
                        }
                    }
                }
            });
        } catch (Throwable e) {
            Log.w(TAG, "DisplayManagerService hook optional fallback: " + e.getMessage());
        }
    }

    public static void updateInjectedAppLabels(Context sysContext) {
        if (sysContext == null) return;
        try {
            java.util.List<String> pkgs = AppManager.getInjectedPackagesList(sysContext);
            if (pkgs == null || pkgs.isEmpty()) {
                Log.d(TAG, "updateInjectedAppLabels: No injected packages configured");
                return;
            }

            PackageManager pm = sysContext.getPackageManager();

            Class<?> localServicesClass = Class.forName("com.android.server.LocalServices");
            Method getServiceMethod = localServicesClass.getMethod("getService", Class.class);
            Class<?> pmiClass = Class.forName("android.content.pm.PackageManagerInternal");
            Object pmi = getServiceMethod.invoke(null, pmiClass);

            if (pmi != null) {
                Method getPackageMethod = null;
                for (Method m : pmi.getClass().getMethods()) {
                    if (m.getName().equals("getPackage") && m.getParameterCount() == 1) {
                        getPackageMethod = m;
                        break;
                    }
                }

                if (getPackageMethod != null) {
                    Object androidPkg = getPackageMethod.invoke(pmi, "com.tho493.antscreen");
                    if (androidPkg != null) {
                        Method getServicesMethod = androidPkg.getClass().getMethod("getServices");
                        java.util.List<?> services = (java.util.List<?>) getServicesMethod.invoke(androidPkg);
                        if (services != null) {
                            for (Object parsedService : services) {
                                Method getNameMethod = null;
                                try {
                                    getNameMethod = parsedService.getClass().getMethod("getName");
                                } catch (Throwable ignored) {
                                }
                                String sName = (getNameMethod != null) ? (String) getNameMethod.invoke(parsedService) : null;
                                if (sName == null) continue;

                                int slotIdx = parseSlotIndex(sName);
                                if (slotIdx >= 0 && slotIdx < pkgs.size()) {
                                    String targetPkg = pkgs.get(slotIdx);
                                    try {
                                        ApplicationInfo ai = pm.getApplicationInfo(targetPkg, 0);
                                        CharSequence label = pm.getApplicationLabel(ai);
                                        if (label != null) {
                                            setFieldValue(parsedService, "nonLocalizedLabel", label);
                                            Log.i(TAG, "Assigned label '" + label + "' to " + sName + " (slot " + slotIdx + ")");
                                        }
                                    } catch (Throwable e) {
                                        Log.w(TAG, "Failed to resolve label for " + targetPkg + ": " + e.getMessage());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "updateInjectedAppLabels failed: " + e.getMessage());
        }
    }

    private static int parseSlotIndex(String className) {
        String prefix = "InjectedAppServices$Slot_";
        int idx = className.indexOf(prefix);
        if (idx != -1) {
            try {
                return Integer.parseInt(className.substring(idx + prefix.length()));
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static void setFieldValue(Object target, String fieldName, Object value) {
        Class<?> clazz = target.getClass();
        String mFieldName = "m" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        while (clazz != null && clazz != Object.class) {
            try {
                java.lang.reflect.Field f = null;
                try {
                    f = clazz.getDeclaredField(fieldName);
                } catch (NoSuchFieldException e) {
                    try {
                        f = clazz.getDeclaredField(mFieldName);
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                if (f != null) {
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                }
            } catch (Throwable ignored) {
            }
            clazz = clazz.getSuperclass();
        }
    }

}
