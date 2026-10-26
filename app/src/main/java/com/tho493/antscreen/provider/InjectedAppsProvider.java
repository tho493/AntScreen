package com.tho493.antscreen.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tho493.antscreen.engine.AppManager;

import java.util.ArrayList;

public class InjectedAppsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.tho493.antscreen.injectedapps";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    public static final String METHOD_GET_INJECTED = "get_injected_apps";
    public static final String KEY_APPS = "apps";

    public static final String METHOD_GET_SETTINGS = "get_settings";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (METHOD_GET_INJECTED.equals(method)) {
            Bundle bundle = new Bundle();
            ArrayList<String> list = new ArrayList<>(AppManager.getInjectedPackagesList(getContext()));
            bundle.putStringArrayList(KEY_APPS, list);
            return bundle;
        } else if (METHOD_GET_SETTINGS.equals(method)) {
            Bundle bundle = new Bundle();
            boolean isFullscreen = com.tho493.antscreen.engine.SettingsManager.isFullscreenMode(getContext());
            bundle.putBoolean(com.tho493.antscreen.engine.SettingsManager.KEY_FULLSCREEN, isFullscreen);
            return bundle;
        }
        return super.call(method, arg, extras);
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
