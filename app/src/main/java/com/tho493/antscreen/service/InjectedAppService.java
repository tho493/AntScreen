package com.tho493.antscreen.service;

import android.os.Bundle;
import android.util.Log;

import com.tho493.antscreen.engine.AppManager;
import com.tho493.antscreen.ui.CarDisplayActivity;

public abstract class InjectedAppService extends AaActivityService {
    private static final String TAG = "AntScreen_InjectedService";

    protected abstract int getSlotIndex();

    @Override
    public void onCreate() {
        super.onCreate();
        triggerLaunch();
    }

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        triggerLaunch();
        return super.onStartCommand(intent, flags, startId);
    }

    private void triggerLaunch() {
        int slot = getSlotIndex();
        String targetPkg = AppManager.getPackageForSlot(this, slot);
        Log.i(TAG, "InjectedAppService slot " + slot + " triggerLaunch for package: " + targetPkg);
        if (targetPkg != null) {
            CarDisplayActivity.setPendingLaunchPackage(targetPkg);
        }
    }
}
