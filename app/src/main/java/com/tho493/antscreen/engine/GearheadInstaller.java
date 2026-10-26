package com.tho493.antscreen.engine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class GearheadInstaller {
    private static final String TAG = "GearheadInstaller";

    // URL mặc định bản chuẩn Android Auto 17.6 (arm64-v8a / universal release)
    public static final String DEFAULT_GEARHEAD_URL =
            "https://github.com/tho493/AntScreen/releases/download/v1.0.0/gearhead_17.6.663424.apk";

    public interface InstallCallback {
        void onProgress(int progress, String statusText);
        void onSuccess(String message);
        void onError(String errorMessage);
    }

    public static void startDownloadAndInstall(Context context, String downloadUrl, InstallCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            File targetApk = new File(context.getCacheDir(), "gearhead_17.6.apk");
            HttpURLConnection conn = null;
            InputStream is = null;
            FileOutputStream fos = null;

            try {
                mainHandler.post(() -> callback.onProgress(5, "Đang kết nối tới máy chủ tải về..."));

                URL url = new URL(downloadUrl != null && !downloadUrl.trim().isEmpty() ? downloadUrl : DEFAULT_GEARHEAD_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new Exception("Máy chủ phản hồi mã lỗi: " + responseCode + " (" + conn.getResponseMessage() + ")");
                }

                int fileLength = conn.getContentLength();
                is = conn.getInputStream();
                fos = new FileOutputStream(targetApk);

                byte[] buffer = new byte[8192];
                long totalBytes = 0;
                int bytesRead;
                long lastUpdate = System.currentTimeMillis();

                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;

                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > 100 || totalBytes == fileLength) {
                        lastUpdate = now;
                        int percent = (fileLength > 0) ? (int) ((totalBytes * 80) / fileLength) : 50;
                        String status = String.format("Đang tải: %.1f MB", (totalBytes / (1024.0 * 1024.0)));
                        if (fileLength > 0) {
                            status += String.format(" / %.1f MB (%d%%)", (fileLength / (1024.0 * 1024.0)), (int)((totalBytes * 100) / fileLength));
                        }
                        String finalStatus = status;
                        mainHandler.post(() -> callback.onProgress(percent, finalStatus));
                    }
                }

                fos.flush();
                fos.close();
                fos = null;
                is.close();
                is = null;

                mainHandler.post(() -> callback.onProgress(85, "Tải xong. Đang cài đặt qua quyền Root..."));

                // Chạy lệnh cài đặt ngầm bằng quyền root
                installApkViaRoot(targetApk, mainHandler, callback);

            } catch (Throwable e) {
                Log.e(TAG, "Download or install failed", e);
                mainHandler.post(() -> callback.onError("Lỗi: " + e.getMessage()));
            } finally {
                try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
                try { if (is != null) is.close(); } catch (Throwable ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    public static void installLocalApk(File apkFile, InstallCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            mainHandler.post(() -> callback.onProgress(60, "Đang cài đặt file APK đã chọn qua Root..."));
            installApkViaRoot(apkFile, mainHandler, callback);
        }).start();
    }

    private static void installApkViaRoot(File apkFile, Handler mainHandler, InstallCallback callback) {
        try {
            // Cho phép đọc file
            apkFile.setReadable(true, false);

            String cmd = "pm install -r -d " + apkFile.getAbsolutePath();
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            while ((line = errReader.readLine()) != null) sb.append(line).append("\n");

            int exitCode = process.waitFor();
            String output = sb.toString().trim();

            if (exitCode == 0 && output.contains("Success")) {
                mainHandler.post(() -> {
                    callback.onProgress(100, "Cài đặt thành công!");
                    callback.onSuccess("Đã cài đặt thành công Android Auto phiên bản 17.6!");
                });
            } else {
                mainHandler.post(() -> callback.onError("Cài đặt thất bại (Mã lỗi " + exitCode + "): " + output));
            }
        } catch (Throwable t) {
            Log.e(TAG, "Root install error", t);
            mainHandler.post(() -> callback.onError("Không thể thực thi lệnh Root: " + t.getMessage()));
        }
    }
}
