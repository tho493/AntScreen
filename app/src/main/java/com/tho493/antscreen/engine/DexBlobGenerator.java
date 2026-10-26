package com.tho493.antscreen.engine;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DexBlobGenerator {
    private static final String TAG = "DexBlobGenerator";

    public static boolean generate(Context context) {
        try {
            String apkPath = context.getPackageCodePath();
            File apkFile = new File(apkPath);
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file not found: " + apkPath);
                return false;
            }

            List<ZipEntry> dexEntries = new ArrayList<>();
            try (ZipFile zipFile = new ZipFile(apkFile)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith("classes") && name.endsWith(".dex")) {
                        dexEntries.add(entry);
                    }
                }

                // Sort by name (classes.dex, classes2.dex, ...)
                Collections.sort(dexEntries, (a, b) -> a.getName().compareTo(b.getName()));

                List<byte[]> dexBytesList = new ArrayList<>();
                for (ZipEntry entry : dexEntries) {
                    try (InputStream is = zipFile.getInputStream(entry);
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = is.read(buf)) != -1) {
                            baos.write(buf, 0, r);
                        }
                        dexBytesList.add(baos.toByteArray());
                    }
                }

                // Output to /data/user_de/0/com.tho493.antscreen/files/payload.dexblob
                Context deContext = context.createDeviceProtectedStorageContext();
                File deFilesDir = deContext.getFilesDir();
                if (!deFilesDir.exists()) {
                    deFilesDir.mkdirs();
                }
                File targetBlob = new File(deFilesDir, "payload.dexblob");

                try (FileOutputStream fos = new FileOutputStream(targetBlob);
                     DataOutputStream dos = new DataOutputStream(fos)) {
                    // 1. Count (Big-Endian uint32)
                    dos.writeInt(dexBytesList.size());

                    // 2. Lengths array (Big-Endian uint32 each)
                    for (byte[] dex : dexBytesList) {
                        dos.writeInt(dex.length);
                    }

                    // 3. DEX contents
                    for (byte[] dex : dexBytesList) {
                        dos.write(dex);
                    }
                    dos.flush();
                }

                targetBlob.setReadable(true, false);
                Log.i(TAG, "Successfully generated payload.dexblob: " + targetBlob.length() + " bytes");
                return true;
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to generate payload.dexblob", e);
            return false;
        }
    }
}
