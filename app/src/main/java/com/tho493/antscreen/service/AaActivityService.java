package com.tho493.antscreen.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.apps.auto.sdk.CarActivityService;
import com.tho493.antscreen.IMirrorManager;
import com.tho493.antscreen.hook.auto.AaCoolwalkUiHook;
import com.tho493.antscreen.ipc.MirrorManagerClient;
import com.tho493.antscreen.ui.CarDisplayActivity;

public class AaActivityService extends CarActivityService {
    private static final String TAG = "AntScreen_AaService";

    private final BroadcastReceiver mControlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !AaCoolwalkUiHook.ACTION_SCREEN_CONTROL.equals(intent.getAction())) {
                return;
            }

            int keyCode = intent.getIntExtra(AaCoolwalkUiHook.EXTRA_ACTION, -1);
            Log.i(TAG, "onReceive control keyCode=" + keyCode);

            IMirrorManager service = MirrorManagerClient.getService();

            try {
                switch (keyCode) {
                    case 3: // KEYCODE_HOME
                        CarDisplayActivity.showHome();
                        break;
                    case 4: // KEYCODE_BACK
                        if (CarDisplayActivity.isRecentsOverlayVisible()) {
                            CarDisplayActivity.hideRecentsOverlay();
                            Log.d(TAG, "Back closed Recents Overlay");
                            return;
                        }
                        if (CarDisplayActivity.isAppDrawerVisible()) {
                            Log.d(TAG, "Back on AppDrawer -> Opening Android Auto App Drawer");
                            CarDisplayActivity.hideAppDrawer();
                            try {
                                Intent openAa = new Intent(AaCoolwalkUiHook.ACTION_OPEN_AA_DRAWER);
                                openAa.setPackage("com.google.android.projection.gearhead");
                                sendBroadcast(openAa);
                            } catch (Throwable ignored) {
                            }
                            return;
                        }
                        if (service != null) {
                            service.handleBack();
                            CarDisplayActivity.checkAndRestoreDrawerIfEmpty();
                        }
                        break;
                    case 187: // KEYCODE_APP_SWITCH (Recents)
                        CarDisplayActivity.toggleRecentsOverlay();
                        break;
                    case AaCoolwalkUiHook.KEY_APPS_DRAWER:
                        CarDisplayActivity.toggleAppDrawer();
                        break;
                    default:
                        break;
                }
            } catch (Throwable e) {
                Log.e(TAG, "Error handling control action: " + keyCode, e);
            }
        }
    };

    @Override
    public Class<? extends CarActivity> getCarActivity() {
        return CarDisplayActivity.class;
    }

    @Override
    public int getHandledConfigChanges() {
        return 0x50007fff;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "AaActivityService started in projection process");
        MirrorManagerClient.init(this, null);

        IntentFilter filter = new IntentFilter(AaCoolwalkUiHook.ACTION_SCREEN_CONTROL);
        ContextCompat.registerReceiver(this, mControlReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(mControlReceiver);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
        Log.i(TAG, "AaActivityService destroyed");
    }
}
