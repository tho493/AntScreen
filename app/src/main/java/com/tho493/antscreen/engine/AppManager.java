package com.tho493.antscreen.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.util.Log;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppManager {
    private static final String TAG = "AntScreen_AppManager";
    public static final String INJECTED_FILE_PATH = "/data/user_de/0/com.tho493.antscreen/files/injected_apps.json";
    public static final String SHARED_FILE_PATH = "/data/local/tmp/antscreen_injected_apps.json";

    public static class AppItem {
        public final String packageName;
        public final String appName;
        public final Drawable icon;
        public boolean isEnabled;

        public AppItem(String packageName, String appName, Drawable icon, boolean isEnabled) {
            this.packageName = packageName;
            this.appName = appName;
            this.icon = icon;
            this.isEnabled = isEnabled;
        }
    }

    public static File getInjectedAppsFile(Context context) {
        if (context != null) {
            try {
                Context deContext = context.createDeviceProtectedStorageContext();
                File dir = deContext.getFilesDir();
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                return new File(dir, "injected_apps.json");
            } catch (Throwable e) {
                Log.w(TAG, "Fallback to default storage: " + e.getMessage());
            }
        }
        File primary = new File(INJECTED_FILE_PATH);
        if (primary.exists() && primary.canRead()) {
            return primary;
        }
        return new File(SHARED_FILE_PATH);
    }

    private static volatile List<String> sCachedInjectedList = new ArrayList<>();
    private static volatile long sLastCacheTime = 0;
    private static final long CACHE_TTL_MS = 10000; // 10 seconds cache to avoid IPC spam

    public static synchronized Set<String> getInjectedPackages(Context context) {
        return new HashSet<>(getInjectedPackagesList(context));
    }

    public static synchronized List<String> getInjectedPackagesList(Context context) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (sCachedInjectedList != null && !sCachedInjectedList.isEmpty() && (now - sLastCacheTime < CACHE_TTL_MS)) {
            return new ArrayList<>(sCachedInjectedList);
        }

        if (context != null && !"com.tho493.antscreen".equals(context.getPackageName())) {
            try {
                android.net.Uri uri = android.net.Uri.parse("content://com.tho493.antscreen.injectedapps");
                android.os.Bundle bundle = context.getContentResolver().call(uri, "get_injected_apps", null, null);
                if (bundle != null && bundle.containsKey("apps")) {
                    ArrayList<String> remoteList = bundle.getStringArrayList("apps");
                    if (remoteList != null) {
                        sCachedInjectedList = new ArrayList<>(remoteList);
                        sLastCacheTime = now;
                        return new ArrayList<>(remoteList);
                    }
                }
            } catch (Throwable e) {
                // Keep existing cache on failure and throttle retries
                sLastCacheTime = now - CACHE_TTL_MS + 2000; // Retry in 2s if failed
                Log.w(TAG, "ContentProvider call failed from " + context.getPackageName() + ": " + e.getMessage());
            }
        }

        List<String> list = new ArrayList<>();
        File file = getInjectedAppsFile(context);
        if (!file.exists()) {
            file = new File(INJECTED_FILE_PATH);
        }
        if (!file.exists() || file.length() == 0) {
            if (sCachedInjectedList != null && !sCachedInjectedList.isEmpty()) {
                return new ArrayList<>(sCachedInjectedList);
            }
            return list;
        }

        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int read;
            while ((read = reader.read(buf)) != -1) {
                sb.append(buf, 0, read);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
            sCachedInjectedList = new ArrayList<>(list);
            sLastCacheTime = now;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to read injected packages list", e);
            if (sCachedInjectedList != null && !sCachedInjectedList.isEmpty()) {
                return new ArrayList<>(sCachedInjectedList);
            }
        }
        return list;
    }

    public static String getPackageForSlot(Context context, int slotIndex) {
        List<String> list = getInjectedPackagesList(context);
        if (slotIndex >= 0 && slotIndex < list.size()) {
            return list.get(slotIndex);
        }
        return null;
    }

    public static final int MAX_SLOTS = 50;

    public static void syncComponentState(Context context) {
        if (context == null) return;
        try {
            PackageManager pm = context.getPackageManager();
            List<String> list = getInjectedPackagesList(context);
            for (int i = 0; i < MAX_SLOTS; i++) {
                ComponentName cn = new ComponentName(context,
                        "com.tho493.antscreen.service.InjectedAppServices$Slot_" + i);
                int state = (i < list.size())
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                try {
                    pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
                } catch (Throwable ignored) {
                }
            }
            Log.i(TAG, "Synced " + list.size() + " injected app slots to PackageManager");
        } catch (Throwable e) {
            Log.w(TAG, "syncComponentState error: " + e.getMessage());
        }
    }

    public static boolean isPackageInjected(String packageName) {
        return isPackageInjected(null, packageName);
    }

    public static boolean isPackageInjected(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        Set<String> set = getInjectedPackages(context);
        return set.contains(packageName);
    }

    public static synchronized void saveInjectedPackages(Context context, Set<String> packages) {
        File file = getInjectedAppsFile(context);
        try {
            JSONArray arr = new JSONArray();
            List<String> newList = new ArrayList<>();
            if (packages != null) {
                for (String pkg : packages) {
                    arr.put(pkg);
                    newList.add(pkg);
                }
            }
            sCachedInjectedList = newList;
            String content = arr.toString();
            try (FileOutputStream fos = new FileOutputStream(file);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(content);
                writer.flush();
            }
            file.setReadable(true, false);

            try {
                File sharedFile = new File(SHARED_FILE_PATH);
                try (FileOutputStream fos2 = new FileOutputStream(sharedFile);
                     OutputStreamWriter writer2 = new OutputStreamWriter(fos2, StandardCharsets.UTF_8)) {
                    writer2.write(content);
                    writer2.flush();
                }
                sharedFile.setReadable(true, false);
                sharedFile.setWritable(true, false);
            } catch (Throwable ignored) {
            }

            // Dùng quyền root để đảm bảo quyền truy cập cho mọi tiến trình (Gearhead, system_server)
            new Thread(() -> {
                try {
                    String cmd = "chmod 755 /data/user_de/0/com.tho493.antscreen && " +
                            "chmod 755 /data/user_de/0/com.tho493.antscreen/files && " +
                            "chmod 666 " + INJECTED_FILE_PATH + " && " +
                            "mkdir -p /data/local/tmp && " +
                            "cp " + INJECTED_FILE_PATH + " " + SHARED_FILE_PATH + " && " +
                            "chmod 666 " + SHARED_FILE_PATH;
                    Runtime.getRuntime().exec(new String[]{"su", "-c", cmd}).waitFor();
                } catch (Throwable ignored) {
                }
            }).start();

            Log.i(TAG, "Saved " + arr.length() + " injected packages to " + file.getAbsolutePath());
            if (context != null) {
                syncComponentState(context);
                try {
                    Intent syncIntent = new Intent("com.tho493.antscreen.action.REQUEST_BINDER");
                    context.sendBroadcast(syncIntent);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to save injected packages", e);
        }
    }

    public static synchronized void setPackageInjected(Context context, String packageName, boolean injected) {
        Set<String> set = getInjectedPackages(context);
        if (injected) {
            set.add(packageName);
        } else {
            set.remove(packageName);
        }
        saveInjectedPackages(context, set);
    }

    public static List<AppItem> getInstalledApps(Context context) {
        List<AppItem> list = new ArrayList<>();
        if (context == null) return list;

        PackageManager pm = context.getPackageManager();
        Set<String> enabledPackages = getInjectedPackages(context);

        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = pm.queryIntentActivities(mainIntent, 0);

        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : activities) {
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(context.getPackageName()) || seen.contains(pkg)) {
                continue;
            }
            seen.add(pkg);

            String name = ri.loadLabel(pm).toString();
            Drawable icon = ri.loadIcon(pm);
            boolean enabled = enabledPackages.contains(pkg);
            list.add(new AppItem(pkg, name, icon, enabled));
        }

        Collections.sort(list, (a, b) -> a.appName.compareToIgnoreCase(b.appName));
        return list;
    }
}
