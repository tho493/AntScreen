package com.tho493.antscreen.ui;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.apps.auto.sdk.CarActivity;
import com.tho493.antscreen.IMirrorManager;
import com.tho493.antscreen.engine.AppManager;
import com.tho493.antscreen.hook.auto.AaCoolwalkUiHook;
import com.tho493.antscreen.ipc.MirrorManagerClient;
import com.tho493.antscreen.projection.TouchCoordinateMapper;

import java.util.ArrayList;
import java.util.List;

public class CarDisplayActivity extends CarActivity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "AntScreen_CarDisplay";
    private static CarDisplayActivity sActiveInstance;

    private FrameLayout mRootLayout;
    private TextureView mTextureView;
    private Surface mSurface;
    private VirtualDisplay mVirtualDisplay;
    private DisplayManager mDisplayManager;

    private FrameLayout mDrawerOverlay;
    private LinearLayout mDrawerContainer;
    private FrameLayout mRecentsOverlay;
    private LinearLayout mRecentsContainer;

    private final TouchCoordinateMapper mTouchMapper = new TouchCoordinateMapper();
    private int mPhoneWidth = 1080;
    private int mPhoneHeight = 2400;

    public static volatile String sPendingLaunchPackage = null;

    public static void setPendingLaunchPackage(String pkg) {
        sPendingLaunchPackage = pkg;
        if (sActiveInstance != null && pkg != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> sActiveInstance.launchPackage(pkg));
        }
    }

    private static final List<String> sRecentPackages = new java.util.ArrayList<>();

    public static synchronized void recordLaunchedPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.contains("antscreen") || pkg.contains("gearhead")) return;
        sRecentPackages.remove(pkg);
        sRecentPackages.add(0, pkg);
    }

    public static synchronized List<String> getRecentPackages() {
        return new java.util.ArrayList<>(sRecentPackages);
    }

    public static synchronized void removeRecentPackage(String pkg) {
        sRecentPackages.remove(pkg);
    }

    public static synchronized void clearRecentPackages() {
        sRecentPackages.clear();
    }

    public static int getCurrentVirtualDisplayId() {
        if (sActiveInstance != null && sActiveInstance.mVirtualDisplay != null && sActiveInstance.mVirtualDisplay.getDisplay() != null) {
            return sActiveInstance.mVirtualDisplay.getDisplay().getDisplayId();
        }
        return -1;
    }

    public static void showHome() {
        if (isRecentsOverlayVisible()) {
            hideRecentsOverlay();
        }
        if (isAppDrawerVisible()) {
            hideAppDrawer();
        }
    }

    public static boolean isRecentsOverlayVisible() {
        return sActiveInstance != null && sActiveInstance.mRecentsOverlay != null
                && sActiveInstance.mRecentsOverlay.getVisibility() == View.VISIBLE;
    }

    public static boolean hideRecentsOverlay() {
        if (sActiveInstance != null && sActiveInstance.mRecentsOverlay != null
                && sActiveInstance.mRecentsOverlay.getVisibility() == View.VISIBLE) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (sActiveInstance != null && sActiveInstance.mRecentsOverlay != null) {
                    sActiveInstance.mRecentsOverlay.setVisibility(View.GONE);
                }
            });
            return true;
        }
        return false;
    }

    public static void showRecentsOverlay() {
        if (sActiveInstance != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (sActiveInstance != null) {
                    sActiveInstance.hideAppDrawer();
                    sActiveInstance.populateRecentsApps();
                    if (sActiveInstance.mRecentsOverlay != null) {
                        sActiveInstance.mRecentsOverlay.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
    }

    public static void toggleRecentsOverlay() {
        if (isRecentsOverlayVisible()) {
            hideRecentsOverlay();
        } else {
            showRecentsOverlay();
        }
    }

    public static void hideAllOverlays() {
        hideRecentsOverlay();
        hideAppDrawer();
    }

    public static boolean isAppDrawerVisible() {
        return sActiveInstance != null && sActiveInstance.mDrawerOverlay != null
                && sActiveInstance.mDrawerOverlay.getVisibility() == View.VISIBLE;
    }

    public static boolean hideAppDrawer() {
        if (sActiveInstance != null && sActiveInstance.mDrawerOverlay != null
                && sActiveInstance.mDrawerOverlay.getVisibility() == View.VISIBLE) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (sActiveInstance != null && sActiveInstance.mDrawerOverlay != null) {
                    sActiveInstance.mDrawerOverlay.setVisibility(View.GONE);
                }
            });
            return true;
        }
        return false;
    }

    public static void showAppDrawer() {
        if (sActiveInstance != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (sActiveInstance != null) {
                    hideRecentsOverlay();
                    sActiveInstance.populateDrawerApps();
                    if (sActiveInstance.mDrawerOverlay != null) {
                        sActiveInstance.mDrawerOverlay.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
    }

    public static void toggleAppDrawer() {
        if (sActiveInstance != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(sActiveInstance::showHideAppDrawer);
        }
    }

    public static void launchPackageOnCar(String packageName) {
        if (sActiveInstance != null) {
            sActiveInstance.launchPackage(packageName);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sActiveInstance = this;
        Log.i(TAG, "onCreate: CarDisplayActivity initializing on car screen");

        try {
            setIgnoreConfigChanges(0xffff);
        } catch (Throwable ignored) {
        }

        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                mPhoneWidth = dm.widthPixels;
                mPhoneHeight = dm.heightPixels;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to get phone display metrics: " + e.getMessage());
        }

        mDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);

        mRootLayout = new FrameLayout(this);
        mRootLayout.setBackgroundColor(Color.BLACK);
        mRootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // 1. TextureView for Phone Mirroring / App Virtual Display
        mTextureView = new TextureView(this);
        mTextureView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        mTextureView.setSurfaceTextureListener(this);
        mTextureView.setOnTouchListener(this::handleTouch);
        mRootLayout.addView(mTextureView);

        // 2. Build In-Car App Drawer Overlay & Recents Overlay
        setupDrawerOverlay();
        setupRecentsOverlay();

        // 3. Floating Navigation Bar đã được chuyển sang inject trực tiếp vào rail Android Auto
        // setupCarNavigationBar();

        setContentView(mRootLayout);

        try {
            android.view.Window w = c();
            if (w != null) {
                w.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
                w.setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
                Log.i(TAG, "Applied immersive fullscreen flags to car window");
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed setting immersive fullscreen on car window: " + e.getMessage());
        }
    }

    @Override
    public void setContentView(View view) {
        boolean attached = false;
        try {
            super.setContentView(view);
            attached = true;
            Log.i(TAG, "Attached content view directly via super.setContentView");
        } catch (Throwable t) {
            Log.w(TAG, "super.setContentView failed: " + t.getMessage());
        }

        if (!attached) {
            try {
                android.view.Window w = c();
                if (w != null) {
                    w.setContentView(view);
                    attached = true;
                    Log.i(TAG, "Attached content view directly via Window c()");
                }
            } catch (Throwable e) {
                Log.w(TAG, "Window c().setContentView failed: " + e.getMessage());
            }
        }

        if (!attached) {
            try {
                java.lang.reflect.Field hostField = com.google.android.gms.car.c.class.getDeclaredField("a");
                hostField.setAccessible(true);
                Object host = hostField.get(this);
                if (host != null) {
                    java.lang.reflect.Method setMethod = host.getClass().getMethod("setContentView", View.class);
                    setMethod.invoke(host, view);
                    attached = true;
                    Log.i(TAG, "Attached content view via CarActivityHost");
                }
            } catch (Throwable e) {
                Log.w(TAG, "CarActivityHost reflection failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void setContentView(int layoutResID) {
        try {
            View v = getLayoutInflater().inflate(layoutResID, null);
            setContentView(v);
        } catch (Throwable e) {
            Log.w(TAG, "setContentView(int) failed: " + e.getMessage());
        }
    }

    private LinearLayout mNavBar;
    private View mNavEdgeTab;
    private final android.os.Handler mNavCollapseHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mCollapseNavTask = this::collapseNavBar;

    private void collapseNavBar() {
        if (mNavBar != null && mNavEdgeTab != null) {
            mNavBar.setVisibility(View.GONE);
            mNavEdgeTab.setVisibility(View.VISIBLE);
        }
    }

    private void expandNavBar() {
        if (mNavBar != null && mNavEdgeTab != null) {
            mNavEdgeTab.setVisibility(View.GONE);
            mNavBar.setVisibility(View.VISIBLE);
            resetNavCollapseTimer();
        }
    }

    private void resetNavCollapseTimer() {
        mNavCollapseHandler.removeCallbacks(mCollapseNavTask);
        mNavCollapseHandler.postDelayed(mCollapseNavTask, 3500);
    }

    private void setupCarNavigationBar() {
        // 1. Edge Tab (tay nắm mỏng siêu nhỏ ở mép màn hình, không chiếm diện tích)
        mNavEdgeTab = new View(this);
        GradientDrawable tabBg = new GradientDrawable();
        tabBg.setColor(0x8038BDF8); // semi-transparent cyan pill
        tabBg.setCornerRadii(new float[]{0, 0, 16, 16, 16, 16, 0, 0});
        mNavEdgeTab.setBackground(tabBg);

        FrameLayout.LayoutParams tabLp = new FrameLayout.LayoutParams(12, 72, Gravity.START | Gravity.CENTER_VERTICAL);
        mNavEdgeTab.setLayoutParams(tabLp);
        mNavEdgeTab.setClickable(true);
        mNavEdgeTab.setFocusable(true);
        mNavEdgeTab.setOnClickListener(v -> expandNavBar());
        mRootLayout.addView(mNavEdgeTab);

        // 2. Full Nav Bar (chỉ bung ra khi chạm vào tab, tự thu gọn sau 3.5s)
        mNavBar = new LinearLayout(this);
        mNavBar.setOrientation(LinearLayout.VERTICAL);
        mNavBar.setGravity(Gravity.CENTER);
        mNavBar.setVisibility(View.GONE); // Mặc định thu gọn

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD90F172A);
        bg.setCornerRadius(32);
        bg.setStroke(2, 0xFF38BDF8);
        mNavBar.setBackground(bg);
        mNavBar.setPadding(6, 8, 6, 8);

        // 1. Home
        mNavBar.addView(createNavTextButton("⌂", 20, () -> {
            sendNavKey(3);
            collapseNavBar();
        }));
        // 2. Recents
        mNavBar.addView(createNavTextButton("⧉", 18, () -> {
            sendNavKey(187);
            collapseNavBar();
        }));
        // 3. Back
        mNavBar.addView(createNavTextButton("‹", 24, () -> {
            sendNavKey(4);
            collapseNavBar();
        }));
        // 4. Apps Drawer
        mNavBar.addView(createNavTextButton("⊞", 20, () -> {
            showHideAppDrawer();
            collapseNavBar();
        }));
        // 5. Nút thu gọn nhanh
        mNavBar.addView(createNavTextButton("◀", 14, this::collapseNavBar));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL
        );
        lp.setMargins(6, 0, 0, 0);
        mRootLayout.addView(mNavBar, lp);
    }

    private View createNavTextButton(String symbol, int textSizeSp, Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(symbol);
        tv.setTextSize(textSizeSp);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(14, 12, 14, 12);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(v -> {
            if (onClick != null) {
                onClick.run();
            }
        });
        return tv;
    }

    private void sendNavKey(int keyCode) {
        if (keyCode == 4) {
            if (hideRecentsOverlay()) return;
            if (isAppDrawerVisible()) {
                hideAppDrawer();
                try {
                    Intent openAa = new Intent(AaCoolwalkUiHook.ACTION_OPEN_AA_DRAWER);
                    openAa.setPackage("com.google.android.projection.gearhead");
                    sendBroadcast(openAa);
                } catch (Throwable ignored) {
                }
                return;
            }
        }

        IMirrorManager service = MirrorManagerClient.getService();
        if (service != null) {
            try {
                switch (keyCode) {
                    case 3:
                        showHome();
                        break;
                    case 4:
                        service.handleBack();
                        checkAndRestoreDrawerIfEmpty();
                        break;
                    case 187:
                        toggleRecentsOverlay();
                        break;
                }
            } catch (Throwable e) {
                Log.e(TAG, "sendNavKey failed: " + keyCode, e);
            }
        } else {
            if (keyCode == 3) {
                showHome();
            } else if (keyCode == 187) {
                toggleRecentsOverlay();
            }
        }
    }

    private void setupRecentsOverlay() {
        mRecentsOverlay = new FrameLayout(this);
        mRecentsOverlay.setBackgroundColor(0xF0080C16);
        mRecentsOverlay.setVisibility(View.GONE);
        mRecentsOverlay.setOnClickListener(v -> mRecentsOverlay.setVisibility(View.GONE));

        FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        mRootLayout.addView(mRecentsOverlay, overlayLp);

        mRecentsContainer = new LinearLayout(this);
        mRecentsContainer.setOrientation(LinearLayout.VERTICAL);
        mRecentsContainer.setGravity(Gravity.CENTER);

        FrameLayout.LayoutParams containerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        containerLp.setMargins(40, 20, 40, 20);
        mRecentsOverlay.addView(mRecentsContainer, containerLp);
    }

    private void populateRecentsApps() {
        if (mRecentsContainer == null) return;
        mRecentsContainer.removeAllViews();

        // Top bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, 16);

        TextView title = new TextView(this);
        title.setText("ỨNG DỤNG ĐANG MỞ");
        title.setTextColor(0xFF38BDF8);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        topBar.addView(title, titleLp);

        List<String> packages = getRecentPackages();
        if (packages != null && !packages.isEmpty()) {
            TextView clearAll = new TextView(this);
            clearAll.setText("Đóng tất cả");
            clearAll.setTextColor(0xFFEF4444);
            clearAll.setTextSize(13);
            clearAll.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            clearAll.setPadding(20, 8, 20, 8);
            GradientDrawable clearBg = new GradientDrawable();
            clearBg.setColor(0x22EF4444);
            clearBg.setCornerRadius(16);
            clearBg.setStroke(1, 0x44EF4444);
            clearAll.setBackground(clearBg);
            clearAll.setOnClickListener(v -> {
                clearRecentPackages();
                try {
                    IMirrorManager service = MirrorManagerClient.getService();
                    if (service != null) service.handleHome();
                } catch (Throwable ignored) {}
                hideRecentsOverlay();
            });
            topBar.addView(clearAll);
        }

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setTextSize(18);
        closeBtn.setPadding(20, 0, 0, 0);
        closeBtn.setOnClickListener(v -> hideRecentsOverlay());
        topBar.addView(closeBtn);

        mRecentsContainer.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if (packages == null || packages.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("Không có ứng dụng nào đang mở");
            emptyTv.setTextColor(0xFF64748B);
            emptyTv.setTextSize(14);
            emptyTv.setGravity(Gravity.CENTER);
            emptyTv.setPadding(0, 32, 0, 32);
            mRecentsContainer.addView(emptyTv);
            return;
        }

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);

        LinearLayout appRow = new LinearLayout(this);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setGravity(Gravity.CENTER_VERTICAL);
        hsv.addView(appRow);

        android.content.pm.PackageManager pm = getPackageManager();
        float density = getResources().getDisplayMetrics().density;
        int cardWidth = (int) (180 * density + 0.5f);
        int cardHeight = (int) (210 * density + 0.5f);

        for (String pkg : packages) {
            String appName = pkg;
            android.graphics.drawable.Drawable icon = null;
            try {
                appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                icon = pm.getApplicationIcon(pkg);
            } catch (Throwable ignored) {}

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding((int)(12 * density), (int)(12 * density), (int)(12 * density), (int)(12 * density));

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(0xFF131B2E);
            cardBg.setCornerRadius(16 * density);
            cardBg.setStroke(1, 0xFF23324E);
            card.setBackground(cardBg);

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(cardWidth, cardHeight);
            cardLp.setMargins((int)(8 * density), (int)(6 * density), (int)(8 * density), (int)(6 * density));
            card.setLayoutParams(cardLp);

            // Card Header
            LinearLayout cardHeader = new LinearLayout(this);
            cardHeader.setOrientation(LinearLayout.HORIZONTAL);
            cardHeader.setGravity(Gravity.CENTER_VERTICAL);

            ImageView miniIv = new ImageView(this);
            if (icon != null) miniIv.setImageDrawable(icon);
            cardHeader.addView(miniIv, new LinearLayout.LayoutParams((int)(20 * density), (int)(20 * density)));

            TextView nameTv = new TextView(this);
            nameTv.setText(appName);
            nameTv.setTextColor(Color.WHITE);
            nameTv.setTextSize(12);
            nameTv.setSingleLine(true);
            nameTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            nameTv.setPadding((int)(8 * density), 0, (int)(4 * density), 0);
            cardHeader.addView(nameTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            TextView delBtn = new TextView(this);
            delBtn.setText("✕");
            delBtn.setTextColor(0xFF94A3B8);
            delBtn.setTextSize(13);
            delBtn.setPadding((int)(6 * density), 0, (int)(6 * density), 0);
            delBtn.setOnClickListener(v -> {
                removeRecentPackage(pkg);
                populateRecentsApps();
            });
            cardHeader.addView(delBtn);

            card.addView(cardHeader, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Big Icon
            ImageView bigIv = new ImageView(this);
            if (icon != null) bigIv.setImageDrawable(icon);
            LinearLayout.LayoutParams bigLp = new LinearLayout.LayoutParams((int)(64 * density), (int)(64 * density));
            bigLp.setMargins(0, (int)(16 * density), 0, (int)(14 * density));
            card.addView(bigIv, bigLp);

            // Open button
            TextView openBtn = new TextView(this);
            openBtn.setText("MỞ");
            openBtn.setTextColor(0xFF38BDF8);
            openBtn.setTextSize(12);
            openBtn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            openBtn.setGravity(Gravity.CENTER);
            openBtn.setPadding(0, (int)(6 * density), 0, (int)(6 * density));
            GradientDrawable openBg = new GradientDrawable();
            openBg.setColor(0x2238BDF8);
            openBg.setCornerRadius(10 * density);
            openBtn.setBackground(openBg);
            card.addView(openBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            card.setOnClickListener(v -> {
                hideRecentsOverlay();
                launchPackage(pkg);
            });

            appRow.addView(card);
        }

        mRecentsContainer.addView(hsv);
    }

    private void setupDrawerOverlay() {
        mDrawerOverlay = new FrameLayout(this);
        mDrawerOverlay.setBackgroundColor(0xCC050811); // Dark semi-transparent
        mDrawerOverlay.setVisibility(View.GONE);
        mDrawerOverlay.setOnClickListener(v -> mDrawerOverlay.setVisibility(View.GONE));

        FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        mRootLayout.addView(mDrawerOverlay, overlayLp);

        mDrawerContainer = new LinearLayout(this);
        mDrawerContainer.setOrientation(LinearLayout.VERTICAL);
        mDrawerContainer.setGravity(Gravity.CENTER);

        FrameLayout.LayoutParams containerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        containerLp.setMargins(40, 20, 40, 20);
        mDrawerOverlay.addView(mDrawerContainer, containerLp);
    }

    private void showHideAppDrawer() {
        if (mDrawerOverlay == null) return;
        if (mDrawerOverlay.getVisibility() == View.VISIBLE) {
            mDrawerOverlay.setVisibility(View.GONE);
        } else {
            populateDrawerApps();
            mDrawerOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void populateDrawerApps() {
        mDrawerContainer.removeAllViews();

        TextView title = new TextView(this);
        title.setText("ỨNG DỤNG ĐÃ CHỌN");
        title.setTextColor(0xFF38BDF8);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 24);
        mDrawerContainer.addView(title);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);

        LinearLayout appRow = new LinearLayout(this);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setGravity(Gravity.CENTER_VERTICAL);
        hsv.addView(appRow);

        List<AppManager.AppItem> allApps = AppManager.getInstalledApps(this);
        List<AppManager.AppItem> enabledApps = new ArrayList<>();
        for (AppManager.AppItem item : allApps) {
            if (item.isEnabled) {
                enabledApps.add(item);
            }
        }

        if (enabledApps.isEmpty()) {
            TextView emptyTv = new TextView(this);
            emptyTv.setText("Chưa có ứng dụng nào được bật.\nHãy mở AntScreen trên điện thoại -> Quản lý ứng dụng xe.");
            emptyTv.setTextColor(0xFF94A3B8);
            emptyTv.setTextSize(14);
            emptyTv.setGravity(Gravity.CENTER);
            mDrawerContainer.addView(emptyTv);
            return;
        }

        for (AppManager.AppItem item : enabledApps) {
            LinearLayout itemLayout = new LinearLayout(this);
            itemLayout.setOrientation(LinearLayout.VERTICAL);
            itemLayout.setGravity(Gravity.CENTER);
            itemLayout.setPadding(24, 16, 24, 16);
            itemLayout.setClickable(true);
            itemLayout.setFocusable(true);

            // Icon card
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(item.icon);
            iv.setLayoutParams(new LinearLayout.LayoutParams(96, 96));

            // Name
            TextView tv = new TextView(this);
            tv.setText(item.appName);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(13);
            tv.setMaxLines(1);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, 8, 0, 0);

            itemLayout.addView(iv);
            itemLayout.addView(tv);

            itemLayout.setOnClickListener(v -> {
                mDrawerOverlay.setVisibility(View.GONE);
                launchPackage(item.packageName);
            });

            appRow.addView(itemLayout);
        }

        mDrawerContainer.addView(hsv);
    }

    public void launchPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }

        recordLaunchedPackage(packageName);
        hideRecentsOverlay();

        int displayId = (mVirtualDisplay != null && mVirtualDisplay.getDisplay() != null)
                ? mVirtualDisplay.getDisplay().getDisplayId()
                : 0;

        Log.i(TAG, "launchPackage requested for " + packageName + " on display " + displayId);

        if (displayId <= 0) {
            Log.w(TAG, "VirtualDisplay not ready yet (displayId=" + displayId + "), queuing pending launch for " + packageName);
            sPendingLaunchPackage = packageName;
            return;
        }

        boolean dispatched = false;
        // 1. Launching via MirrorManager Binder service in system_server (0ms delay, no adb)
        IMirrorManager service = MirrorManagerClient.getService();
        if (service != null) {
            try {
                service.launchAppOnDisplay(packageName, displayId);
                dispatched = true;
                Log.i(TAG, "Dispatched launchAppOnDisplay via Binder service to display " + displayId);
            } catch (Throwable e) {
                Log.w(TAG, "service.launchAppOnDisplay error: " + e.getMessage());
            }
        }

        // 2. Direct Android ActivityOptions launch fallback
        if (!dispatched) {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                android.content.Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
                if (launchIntent != null) {
                    launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
                    options.setLaunchDisplayId(displayId);
                    startActivity(launchIntent, options.toBundle());
                    Log.i(TAG, "Launched " + packageName + " directly on display " + displayId + " via ActivityOptions");
                }
            } catch (Throwable t) {
                Log.e(TAG, "Direct launch failed for " + packageName, t);
            }
        }
    }

    private boolean handleTouch(View v, MotionEvent event) {
        IMirrorManager service = MirrorManagerClient.getService();
        if (service == null) {
            return false;
        }

        int action = event.getActionMasked();
        float x = mTouchMapper.mapX(event.getX());
        float y = mTouchMapper.mapY(event.getY());
        int displayId = (mVirtualDisplay != null && mVirtualDisplay.getDisplay() != null)
                ? mVirtualDisplay.getDisplay().getDisplayId()
                : 0;

        try {
            service.injectTouch(action, x, y, displayId);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Error injecting touch", e);
            return false;
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        Log.i(TAG, "onSurfaceTextureAvailable: car screen dimension = " + width + "x" + height);
        createVirtualDisplay(surfaceTexture, width, height);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        Log.i(TAG, "onSurfaceTextureSizeChanged: " + width + "x" + height);
        createVirtualDisplay(surfaceTexture, width, height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        Log.i(TAG, "onSurfaceTextureDestroyed");
        releaseVirtualDisplay();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
    }

    private synchronized void createVirtualDisplay(SurfaceTexture surfaceTexture, int width, int height) {
        if (width <= 0 || height <= 0 || mDisplayManager == null) {
            return;
        }

        releaseVirtualDisplay();

        mSurface = new Surface(surfaceTexture);
        mTouchMapper.updateDimensions(width, height, width, height);

        int densityDpi = Math.min(width, height) >= 720 ? 210 : 160;

        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | 0x00000800; // OWN_DISPLAY_GROUP (independent from phone screen)

        try {
            mVirtualDisplay = mDisplayManager.createVirtualDisplay(
                    "AntScreen_Display",
                    width,
                    height,
                    densityDpi,
                    mSurface,
                    flags
            );
            Log.i(TAG, "AntScreen_Display created with OWN_DISPLAY_GROUP: " + width + "x" + height + " dpi=" + densityDpi);
        } catch (Throwable e) {
            Log.w(TAG, "Failed createVirtualDisplay with OWN_DISPLAY_GROUP, trying PRESENTATION fallback: " + e.getMessage());
            try {
                mVirtualDisplay = mDisplayManager.createVirtualDisplay(
                        "AntScreen_Display",
                        width,
                        height,
                        densityDpi,
                        mSurface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                );
                Log.i(TAG, "AntScreen_Display created with PRESENTATION flag");
            } catch (Throwable ex) {
                Log.w(TAG, "Failed createVirtualDisplay with PRESENTATION, trying default 0 flag: " + ex.getMessage());
                try {
                    mVirtualDisplay = mDisplayManager.createVirtualDisplay(
                            "AntScreen_Display",
                            width,
                            height,
                            densityDpi,
                            mSurface,
                            0
                    );
                    Log.i(TAG, "AntScreen_Display created with 0 flag");
                } catch (Throwable ex2) {
                    Log.e(TAG, "Fatal: failed all virtual display flags", ex2);
                }
            }
        }

        if (mVirtualDisplay != null && mVirtualDisplay.getDisplay() != null) {
            int displayId = mVirtualDisplay.getDisplay().getDisplayId();
            Log.i(TAG, "VirtualDisplay ready with displayId=" + displayId);
            notifyServiceDisplayId(displayId);
        }

        checkPendingLaunchOrDrawer();
    }

    private void notifyServiceDisplayId(int displayId) {
        try {
            IMirrorManager service = MirrorManagerClient.getService();
            if (service != null) {
                service.setCurrentDisplayId(displayId);
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to update currentDisplayId to service: " + e.getMessage());
        }
    }

    private void checkPendingLaunchOrDrawer() {
        if (sPendingLaunchPackage != null) {
            final String pkgToLaunch = sPendingLaunchPackage;
            sPendingLaunchPackage = null;
            mRootLayout.postDelayed(() -> launchPackage(pkgToLaunch), 200);
        } else {
            int count = 0;
            try {
                IMirrorManager service = MirrorManagerClient.getService();
                if (service != null && mVirtualDisplay != null && mVirtualDisplay.getDisplay() != null) {
                    count = service.getRunningTaskCount(mVirtualDisplay.getDisplay().getDisplayId());
                }
            } catch (Throwable ignored) {
            }
            if (count <= 0) {
                mRootLayout.postDelayed(CarDisplayActivity::showAppDrawer, 200);
            }
        }
    }

    public static void checkAndRestoreDrawerIfEmpty() {
        if (sActiveInstance == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (sActiveInstance == null) return;
            if (isRecentsOverlayVisible() || isAppDrawerVisible()) return;
            int displayId = getCurrentVirtualDisplayId();
            if (displayId <= 0) return;
            try {
                IMirrorManager service = MirrorManagerClient.getService();
                if (service != null) {
                    int count = service.getRunningTaskCount(displayId);
                    if (count <= 0) {
                        Log.i(TAG, "No running task on VirtualDisplay -> Restoring App Drawer");
                        showAppDrawer();
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "checkAndRestoreDrawerIfEmpty error: " + e.getMessage());
            }
        }, 350);
    }

    private synchronized void releaseVirtualDisplay() {
        if (mVirtualDisplay != null) {
            try {
                mVirtualDisplay.release();
            } catch (Throwable ignored) {
            }
            mVirtualDisplay = null;
        }
        if (mSurface != null) {
            try {
                mSurface.release();
            } catch (Throwable ignored) {
            }
            mSurface = null;
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.i(TAG, "onStart: CarDisplayActivity active");
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume: CarDisplayActivity resumed");
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.i(TAG, "onPause: CarDisplayActivity paused");
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.i(TAG, "onStop: CarDisplayActivity stopped");
    }

    @Override
    public void onBackPressed() {
        if (isRecentsOverlayVisible()) {
            hideRecentsOverlay();
            return;
        }
        if (isAppDrawerVisible()) {
            hideAppDrawer();
            return;
        }
        try {
            IMirrorManager service = MirrorManagerClient.getService();
            if (service != null) {
                service.handleBack();
                return;
            }
        } catch (Throwable ignored) {
        }
        // Không gọi super.onBackPressed() để tránh đóng CarDisplayActivity gây đen màn hình xe
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: CarDisplayActivity destroying");
        if (sActiveInstance == this) {
            sActiveInstance = null;
        }
        releaseVirtualDisplay();
        super.onDestroy();
    }
}
