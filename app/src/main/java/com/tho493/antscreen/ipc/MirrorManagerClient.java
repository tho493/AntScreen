package com.tho493.antscreen.ipc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.tho493.antscreen.IMirrorManager;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MirrorManagerClient {
    private static final String TAG = "AntScreen_Client";
    private static final String SERVICE_NAME = "antscreen_mirror";
    public static final String ACTION_REQUEST_BINDER = "com.tho493.antscreen.action.REQUEST_BINDER";
    public static final String ACTION_BINDER_RESPONSE = "com.tho493.antscreen.action.BINDER_RESPONSE";

    private static volatile IMirrorManager sService;
    private static volatile boolean sReceiverRegistered = false;
    private static Context sAppContext;

    public static synchronized void init(Context context, Runnable onConnected) {
        if (context == null) return;
        sAppContext = context.getApplicationContext();

        if (!sReceiverRegistered) {
            try {
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        if (intent == null) return;
                        if (ACTION_BINDER_RESPONSE.equals(intent.getAction())) {
                            Bundle extra = intent.getBundleExtra("extra_ipc");
                            if (extra != null) {
                                IBinder binder = extra.getBinder("binder");
                                if (binder != null) {
                                    setBinder(binder);
                                    if (onConnected != null) {
                                        onConnected.run();
                                    }
                                }
                            }
                        }
                    }
                };

                IntentFilter filter = new IntentFilter(ACTION_BINDER_RESPONSE);
                if (Build.VERSION.SDK_INT >= 33) {
                    sAppContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    sAppContext.registerReceiver(receiver, filter);
                }
                sReceiverRegistered = true;
                Log.i(TAG, "Binder response receiver registered in client app");
            } catch (Throwable e) {
                Log.w(TAG, "Failed to register response receiver: " + e.getMessage());
            }
        }

        // Request binder from system_server immediately
        requestBinder(sAppContext);
    }

    public static void requestBinder(Context context) {
        try {
            Context ctx = (context != null) ? context : sAppContext;
            if (ctx != null) {
                Intent request = new Intent(ACTION_REQUEST_BINDER);
                request.putExtra("target_package", ctx.getPackageName());
                ctx.sendBroadcast(request);
                Log.d(TAG, "Sent REQUEST_BINDER broadcast from " + ctx.getPackageName());
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to send REQUEST_BINDER: " + e.getMessage());
        }
    }

    public static synchronized void setBinder(IBinder binder) {
        if (binder == null) return;
        try {
            binder.linkToDeath(() -> {
                synchronized (MirrorManagerClient.class) {
                    sService = null;
                    Log.w(TAG, "Binder died, link severed");
                }
            }, 0);
            sService = IMirrorManager.Stub.asInterface(binder);
            Log.i(TAG, "Direct Binder connected successfully via handshake!");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to link binder death or bind interface", e);
        }
    }

    public static synchronized IMirrorManager getService() {
        if (sService != null && sService.asBinder().isBinderAlive()) {
            return sService;
        }

        // 1. Try handshake request
        if (sAppContext != null) {
            requestBinder(sAppContext);
        }

        // 2. Fallback to ServiceManager (works when running with root, system uid, or shell)
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = smClass.getMethod("getService", String.class);
            IBinder binder = (IBinder) getServiceMethod.invoke(null, SERVICE_NAME);
            if (binder != null) {
                setBinder(binder);
                return sService;
            }
        } catch (Throwable ignored) {
        }

        return sService;
    }
}
