package com.tho493.antscreen.engine;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class SettingsManager {
    private static final String TAG = "AntScreen_Settings";
    private static final String PREF_NAME = "antscreen_settings";
    public static final String KEY_FULLSCREEN = "is_fullscreen";
    public static final String SETTINGS_FILE_PATH = "/data/user_de/0/com.tho493.antscreen/files/settings.json";
    public static final String SHARED_SETTINGS_PATH = "/data/local/tmp/antscreen_settings.json";

    private static volatile Boolean sCachedFullscreen = null;
    private static volatile long sLastCachedTime = 0;

    public static boolean isFullscreenMode(Context context) {
        long now = System.currentTimeMillis();
        if (sCachedFullscreen != null && (now - sLastCachedTime < 1000)) {
            return sCachedFullscreen;
        }

        // 1. Thử ContentProvider nếu chạy từ tiến trình khác (Gearhead)
        if (context != null && !"com.tho493.antscreen".equals(context.getPackageName())) {
            try {
                Uri uri = Uri.parse("content://com.tho493.antscreen.injectedapps");
                Bundle bundle = context.getContentResolver().call(uri, "get_settings", null, null);
                if (bundle != null && bundle.containsKey(KEY_FULLSCREEN)) {
                    boolean val = bundle.getBoolean(KEY_FULLSCREEN, true);
                    sCachedFullscreen = val;
                    sLastCachedTime = now;
                    return val;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. Thử đọc từ SharedPreferences của app
        if (context != null && "com.tho493.antscreen".equals(context.getPackageName())) {
            SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            if (sp.contains(KEY_FULLSCREEN)) {
                boolean val = sp.getBoolean(KEY_FULLSCREEN, true);
                sCachedFullscreen = val;
                sLastCachedTime = now;
                return val;
            }
        }

        // 3. Fallback đọc file JSON từ device-protected hoặc /data/local/tmp
        File file = new File(SETTINGS_FILE_PATH);
        if (!file.exists() || !file.canRead()) {
            file = new File(SHARED_SETTINGS_PATH);
        }

        if (file.exists() && file.canRead()) {
            try (FileInputStream fis = new FileInputStream(file);
                 InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[512];
                int r;
                while ((r = isr.read(buf)) != -1) {
                    sb.append(buf, 0, r);
                }
                JSONObject obj = new JSONObject(sb.toString());
                boolean val = obj.optBoolean(KEY_FULLSCREEN, true);
                sCachedFullscreen = val;
                sLastCachedTime = now;
                return val;
            } catch (Throwable ignored) {
            }
        }

        // Mặc định là mở Full màn hình
        return true;
    }

    public static synchronized void setFullscreenMode(Context context, boolean fullscreen) {
        sCachedFullscreen = fullscreen;
        sLastCachedTime = System.currentTimeMillis();
        if (context != null) {
            try {
                SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                sp.edit().putBoolean(KEY_FULLSCREEN, fullscreen).apply();
                context.getContentResolver().notifyChange(Uri.parse("content://com.tho493.antscreen.injectedapps"), null);
            } catch (Throwable ignored) {
            }
        }

        // Xuất ra file JSON để Gearhead đọc
        new Thread(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put(KEY_FULLSCREEN, fullscreen);
                String content = json.toString();

                File deDir = null;
                if (context != null) {
                    try {
                        deDir = context.createDeviceProtectedStorageContext().getFilesDir();
                    } catch (Throwable ignored) {
                    }
                }
                if (deDir == null) {
                    deDir = new File("/data/user_de/0/com.tho493.antscreen/files");
                }
                if (!deDir.exists()) {
                    deDir.mkdirs();
                }

                File deFile = new File(deDir, "settings.json");
                try (FileOutputStream fos = new FileOutputStream(deFile);
                     OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    osw.write(content);
                    osw.flush();
                }
                deFile.setReadable(true, false);
                deFile.setWritable(true, false);

                // Đồng bộ sang tmp và cấp quyền bằng su
                String cmd = "chmod 666 " + deFile.getAbsolutePath() + " && " +
                        "mkdir -p /data/local/tmp && " +
                        "cp " + deFile.getAbsolutePath() + " " + SHARED_SETTINGS_PATH + " && " +
                        "chmod 666 " + SHARED_SETTINGS_PATH;
                Runtime.getRuntime().exec(new String[]{"su", "-c", cmd}).waitFor();
                Log.i(TAG, "Saved settings: is_fullscreen=" + fullscreen);
            } catch (Throwable e) {
                Log.w(TAG, "Failed to write settings.json: " + e.getMessage());
            }
        }).start();
    }
}
