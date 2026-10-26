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
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MirrorEngineDeployer {
    private static final String TAG = "MirrorEngineDeployer";
    private static final String MODULE_ZIP_ASSET = "antscreen-module.zip";
    public static final String MODULE_TARGET_DIR = "/data/adb/modules/antscreen";

    public interface DeployCallback {
        void onProgress(int progress, String statusText);
        void onSuccess(String message);
        void onError(String errorMessage);
    }

    public static boolean isEngineDeployed() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "[ -f " + MODULE_TARGET_DIR + "/module.prop ] && echo 'yes' || echo 'no'"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String res = r.readLine();
            p.waitFor();
            return res != null && res.trim().equals("yes");
        } catch (Throwable e) {
            return false;
        }
    }

    public static void deployQuick(Context context, DeployCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                mainHandler.post(() -> callback.onProgress(10, "Đang chuẩn bị payload dexblob..."));

                // 1. Tạo dexblob từ APK hiện tại
                boolean dexOk = DexBlobGenerator.generate(context);
                if (!dexOk) {
                    Log.w(TAG, "DexBlobGenerator returned false, proceeding if fallback exists");
                }

                File deBlob = new File(context.createDeviceProtectedStorageContext().getFilesDir(), "payload.dexblob");
                String currentApkPath = context.getPackageCodePath();

                mainHandler.post(() -> callback.onProgress(30, "Đang giải nén các tệp Zygisk module..."));

                // 2. Giải nén module zip từ assets sang cache
                File tempDir = new File(context.getCacheDir(), "magisk_module_temp");
                if (tempDir.exists()) {
                    deleteRecursive(tempDir);
                }
                tempDir.mkdirs();

                unzipAsset(context, MODULE_ZIP_ASSET, tempDir);

                mainHandler.post(() -> callback.onProgress(60, "Đang triển khai vào hệ thống Magisk qua Root..."));

                // 3. Thực thi chép vào /data/adb/modules/antscreen bằng Root
                StringBuilder script = new StringBuilder();
                script.append("mkdir -p ").append(MODULE_TARGET_DIR).append("\n");
                script.append("cp -rf ").append(tempDir.getAbsolutePath()).append("/* ").append(MODULE_TARGET_DIR).append("/\n");
                script.append("cp -f '").append(currentApkPath).append("' ").append(MODULE_TARGET_DIR).append("/payload.apk\n");

                if (deBlob.exists()) {
                    script.append("cp -f '").append(deBlob.getAbsolutePath()).append("' ").append(MODULE_TARGET_DIR).append("/payload.dexblob\n");
                }

                // Cấp quyền
                script.append("chmod -R 755 ").append(MODULE_TARGET_DIR).append("\n");
                script.append("chmod 644 ").append(MODULE_TARGET_DIR).append("/module.prop\n");
                script.append("chmod 644 ").append(MODULE_TARGET_DIR).append("/payload.apk\n");
                if (deBlob.exists()) {
                    script.append("chmod 644 ").append(MODULE_TARGET_DIR).append("/payload.dexblob\n");
                }
                script.append("rm -f ").append(MODULE_TARGET_DIR).append("/disable\n");
                script.append("rm -f ").append(MODULE_TARGET_DIR).append("/zygisk/unloaded\n");
                script.append("touch ").append(MODULE_TARGET_DIR).append("/auto_mount\n");

                File scriptFile = new File(context.getCacheDir(), "deploy_module.sh");
                try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
                    fos.write(script.toString().getBytes());
                    fos.flush();
                }
                scriptFile.setReadable(true, false);
                scriptFile.setExecutable(true, false);

                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "sh " + scriptFile.getAbsolutePath()});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                while ((line = errReader.readLine()) != null) sb.append(line).append("\n");

                int exitCode = p.waitFor();

                // Kiểm tra lại xem module.prop đã xuất hiện trong thư mục đích chưa
                Process checkP = Runtime.getRuntime().exec(new String[]{"su", "-c", "ls -l " + MODULE_TARGET_DIR + "/module.prop"});
                int checkExit = checkP.waitFor();

                if (checkExit == 0) {
                    mainHandler.post(() -> {
                        callback.onProgress(100, "Đã triển khai thành công!");
                        callback.onSuccess("Đã triển khai thành công Mirror Engine vào Magisk!\nKhởi động lại thiết bị.");
                    });
                } else {
                    mainHandler.post(() -> callback.onError("Triển khai thất bại (Mã lỗi " + exitCode + "): " + sb.toString()));
                }

            } catch (Throwable e) {
                Log.e(TAG, "Failed to deploy mirror engine", e);
                mainHandler.post(() -> callback.onError("Lỗi khi triển khai: " + e.getMessage()));
            }
        }).start();
    }

    public static void rebootDevice(DeployCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                if (callback != null) {
                    mainHandler.post(() -> callback.onProgress(50, "Đang khởi động lại thiết bị..."));
                }
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "svc power reboot || reboot"});
                p.waitFor();
                if (callback != null) {
                    mainHandler.post(() -> {
                        callback.onProgress(100, "Đang khởi động lại...");
                        callback.onSuccess("Đang khởi động lại toàn bộ thiết bị...");
                    });
                }
            } catch (Throwable t) {
                Log.e(TAG, "Reboot failed", t);
                if (callback != null) {
                    mainHandler.post(() -> callback.onError("Không thể khởi động lại: " + t.getMessage()));
                }
            }
        }).start();
    }

    public static void restartSystemServer(DeployCallback callback) {
        rebootDevice(callback);
    }

    private static void unzipAsset(Context context, String assetName, File outDir) throws Exception {
        try (InputStream is = context.getAssets().open(assetName);
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                File target = new File(outDir, entry.getName());
                if (entry.isDirectory()) {
                    target.mkdirs();
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    try (OutputStream os = new FileOutputStream(target)) {
                        int r;
                        while ((r = zis.read(buf)) != -1) {
                            os.write(buf, 0, r);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File child : files) deleteRecursive(child);
            }
        }
        f.delete();
    }
}
