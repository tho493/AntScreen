package com.tho493.antscreen.hook.system;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;

import com.tho493.antscreen.IMirrorManager;

import java.lang.reflect.Method;
import java.util.List;

public class MirrorManagerService extends IMirrorManager.Stub {
    private static final String TAG = "AntScreen_MirrorService";
    private final Context mContext;
    private int mCurrentDisplayId = 0;

    public MirrorManagerService(Context context) {
        this.mContext = context;
    }

    public void setCurrentDisplayId(int displayId) {
        this.mCurrentDisplayId = displayId;
    }

    public int getCurrentDisplayId() {
        return mCurrentDisplayId;
    }

    @Override
    public void injectTouch(int action, float x, float y, int displayId) throws RemoteException {
        final long token = android.os.Binder.clearCallingIdentity();
        try {
            int targetDisplay = (displayId > 0) ? displayId : mCurrentDisplayId;
            InputInjector.injectTouch(action, x, y, targetDisplay);
        } finally {
            android.os.Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void handleHome() throws RemoteException {
        final long token = android.os.Binder.clearCallingIdentity();
        try {
            Log.d(TAG, "handleHome requested on display " + mCurrentDisplayId);
            // Không inject KEYCODE_HOME để tránh thoát điện thoại về launcher
            // Thay vào đó, đóng các task ứng dụng bên thứ 3 trên VirtualDisplay để trở về giao diện xe
            removeThirdPartyTasksOnDisplay(mCurrentDisplayId);
        } finally {
            android.os.Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void handleBack() throws RemoteException {
        final long token = android.os.Binder.clearCallingIdentity();
        try {
            Log.d(TAG, "handleBack requested on display " + mCurrentDisplayId);
            if (mCurrentDisplayId <= 0) {
                Log.w(TAG, "handleBack: invalid displayId " + mCurrentDisplayId + ", skipped to protect phone");
                return;
            }
            List<Integer> tasks = getThirdPartyTaskIdsOnDisplay(mCurrentDisplayId);
            if (tasks.isEmpty()) {
                Log.d(TAG, "handleBack: no third-party task on display " + mCurrentDisplayId + ", skipped to protect phone");
                return;
            }

            // Đảm bảo focus vào VirtualDisplay trước khi inject Key
            setFocusedDisplay(mCurrentDisplayId);
            InputInjector.injectKey(KeyEvent.KEYCODE_BACK, mCurrentDisplayId);
        } finally {
            android.os.Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void toggleRecents() throws RemoteException {
        final long token = android.os.Binder.clearCallingIdentity();
        try {
            Log.d(TAG, "toggleRecents requested on display " + mCurrentDisplayId);
            // Không inject KEYCODE_APP_SWITCH để tránh bật Recent Apps của điện thoại
            // Thay vào đó chuyển đổi app trên xe nếu có từ 2 app trở lên
            moveSecondTaskToFront(mCurrentDisplayId);
        } finally {
            android.os.Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public int getRunningTaskCount(int displayId) throws RemoteException {
        final long token = android.os.Binder.clearCallingIdentity();
        try {
            return getThirdPartyTaskIdsOnDisplay(displayId).size();
        } finally {
            android.os.Binder.restoreCallingIdentity(token);
        }
    }

    private List<Integer> getThirdPartyTaskIdsOnDisplay(int displayId) {
        List<Integer> taskIds = new java.util.ArrayList<>();
        if (displayId <= 0) return taskIds;
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Method getServiceMethod = atmClass.getMethod("getService");
            Object atm = getServiceMethod.invoke(null);
            if (atm != null) {
                Method getAllRootTaskInfosMethod = atm.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class);
                List<?> rootTaskInfos = (List<?>) getAllRootTaskInfosMethod.invoke(atm, displayId);
                if (rootTaskInfos != null) {
                    for (Object rootTaskInfo : rootTaskInfos) {
                        java.lang.reflect.Field topActivityField = rootTaskInfo.getClass().getField("topActivity");
                        android.content.ComponentName topAct = (android.content.ComponentName) topActivityField.get(rootTaskInfo);
                        java.lang.reflect.Field taskIdField = rootTaskInfo.getClass().getField("taskId");
                        int taskId = taskIdField.getInt(rootTaskInfo);
                        if (topAct != null) {
                            String pkg = topAct.getPackageName();
                            if (!pkg.contains("antscreen") && !pkg.contains("gearhead")) {
                                taskIds.add(taskId);
                            }
                        }
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "getThirdPartyTaskIdsOnDisplay failed: " + e.getMessage());
        }
        return taskIds;
    }

    private void removeThirdPartyTasksOnDisplay(int displayId) {
        if (displayId <= 0) return;
        try {
            Class<?> atmClass = Class.forName("android.app.ActivityTaskManager");
            Method getServiceMethod = atmClass.getMethod("getService");
            Object atm = getServiceMethod.invoke(null);
            if (atm != null) {
                List<Integer> taskIds = getThirdPartyTaskIdsOnDisplay(displayId);
                Method removeTaskMethod = atm.getClass().getMethod("removeTask", int.class);
                for (int taskId : taskIds) {
                    removeTaskMethod.invoke(atm, taskId);
                    Log.i(TAG, "Removed projected task " + taskId + " on display " + displayId);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "removeThirdPartyTasksOnDisplay failed: " + e.getMessage());
        }
    }

    private void moveSecondTaskToFront(int displayId) {
        if (displayId <= 0) return;
        try {
            List<Integer> taskIds = getThirdPartyTaskIdsOnDisplay(displayId);
            if (taskIds.size() >= 2) {
                int targetTaskId = taskIds.get(1);
                ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    am.moveTaskToFront(targetTaskId, 0);
                    Log.i(TAG, "Brought second task " + targetTaskId + " to front on display " + displayId);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "moveSecondTaskToFront failed: " + e.getMessage());
        }
    }

    private void setFocusedDisplay(int displayId) {
        if (displayId <= 0) return;
        try {
            Class<?> wmClass = Class.forName("android.view.WindowManagerGlobal");
            Method getWmService = wmClass.getMethod("getWindowManagerService");
            Object wm = getWmService.invoke(null);
            if (wm != null) {
                Method[] methods = wm.getClass().getMethods();
                for (Method m : methods) {
                    if (m.getName().equals("setFocusedDisplay")) {
                        if (m.getParameterCount() == 3) {
                            m.invoke(wm, displayId, true, "AntScreen_Nav");
                            return;
                        } else if (m.getParameterCount() == 2) {
                            m.invoke(wm, displayId, true);
                            return;
                        } else if (m.getParameterCount() == 1) {
                            m.invoke(wm, displayId);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void launchAppOnDisplay(String packageName, int displayId) throws RemoteException {
        Log.d(TAG, "launchAppOnDisplay: " + packageName + " on display: " + displayId);
        if (mContext == null || packageName == null || packageName.isEmpty()) {
            return;
        }

        final long token = android.os.Binder.clearCallingIdentity();
        try {
            PackageManager pm = mContext.getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                launchIntent.removeFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                ActivityOptions options = ActivityOptions.makeBasic();
                if (displayId > 0) {
                    options.setLaunchDisplayId(displayId);
                }
                boolean launched = false;
                try {
                    int userId = 0;
                    try {
                        Method getCurUser = ActivityManager.class.getMethod("getCurrentUser");
                        userId = (int) getCurUser.invoke(null);
                    } catch (Throwable ignored) {
                    }
                    Class<?> uhClass = Class.forName("android.os.UserHandle");
                    Method ofMethod = uhClass.getMethod("of", int.class);
                    Object userHandle = ofMethod.invoke(null, userId);

                    Method startMethod = Context.class.getMethod("startActivityAsUser", Intent.class, Bundle.class, uhClass);
                    startMethod.invoke(mContext, launchIntent, options.toBundle(), userHandle);
                    launched = true;
                    Log.i(TAG, "Launched " + packageName + " on display " + displayId + " via startActivityAsUser for user " + userId);
                } catch (Throwable t) {
                    Log.w(TAG, "startActivityAsUser reflection failed: " + t.getMessage());
                }

                if (!launched) {
                    mContext.startActivity(launchIntent, options.toBundle());
                }
            } else {
                Log.w(TAG, "No launch intent found for " + packageName);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to launch app on display " + displayId, e);
        } finally {
            android.os.Binder.restoreCallingIdentity(token);
        }
    }
}
