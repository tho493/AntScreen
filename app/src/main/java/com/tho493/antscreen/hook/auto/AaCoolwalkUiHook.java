package com.tho493.antscreen.hook.auto;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tho493.antscreen.IMirrorManager;
import com.tho493.antscreen.engine.SettingsManager;
import com.tho493.antscreen.ipc.MirrorManagerClient;
import com.tho493.antscreen.ui.CarDisplayActivity;

import java.lang.reflect.Constructor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class AaCoolwalkUiHook {
    private static final String TAG = "AntScreen_CoolwalkUi";
    public static final String ACTION_SCREEN_CONTROL = "com.tho493.antscreen.action.SCREEN_CONTROL";
    public static final String ACTION_OPEN_AA_DRAWER = "com.tho493.antscreen.action.OPEN_AA_DRAWER";
    public static final String EXTRA_ACTION = "com.tho493.antscreen.extra.ACTION";
    public static final String TARGET_PKG = "com.tho493.antscreen";

    public static final int KEY_APPS_DRAWER = 9999;
    private static final String NAVBAR_TAG = "antscreen_rail_navbar";

    private static final java.util.Set<View> sObservedRoots = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.Set<ViewGroup> sInjectedRails = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            hookRailComponents(lpparam);
            hookCoolwalkLayout(lpparam);
            Log.i(TAG, "AaCoolwalkUiHook registered rail observer hooks in " + lpparam.processName);
        } catch (Throwable e) {
            Log.e(TAG, "AaCoolwalkUiHook init failed", e);
        }
    }

    private static void hookCoolwalkLayout(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XC_MethodHook layoutHook = new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Context context = null;
                        try {
                            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
                            context = (Context) appGlobals.getMethod("getInitialApplication").invoke(null);
                        } catch (Throwable ignored) {
                        }

                        boolean isFullscreen = SettingsManager.isFullscreenMode(context);
                        if (!isFullscreen) {
                            Log.i(TAG, "Fullscreen mode disabled by user -> Skipping Coolwalk layout override (split-screen mode enabled)");
                            return;
                        }

                        Object[] args = param.args;
                        if (args == null || args.length < 5) return;

                        boolean isRhd = false;
                        if (args.length > 4 && args[4] instanceof Boolean) {
                            isRhd = (Boolean) args[4];
                        }

                        // 1. Tìm resource ID của vertical rail layout (single pane - toàn màn hình không có dashboard)
                        int targetResId = 0;
                        if (context != null) {
                            String resName = isRhd ? "sys_ui_layout_canonical_vertical_rail_rhd" : "sys_ui_layout_canonical_vertical_rail_lhd";
                            targetResId = context.getResources().getIdentifier(resName, "layout", "com.google.android.projection.gearhead");
                            if (targetResId == 0) {
                                resName = isRhd ? "sys_ui_cielo_layout_canonical_vertical_rail_rhd" : "sys_ui_cielo_layout_canonical_vertical_rail_lhd";
                                targetResId = context.getResources().getIdentifier(resName, "layout", "com.google.android.projection.gearhead");
                            }
                        }

                        if (targetResId == 0) {
                            targetResId = isRhd ? 0x7f0e02c3 : 0x7f0e02c1;
                        }
                        args[0] = targetResId;

                        // 2. Cập nhật layoutType thành STANDARD_VERTICAL_RAIL
                        Object typeArg = args[3];
                        if (typeArg instanceof Enum) {
                            Class<?> enumCls = typeArg.getClass();
                            String targetEnumName = isRhd ? "STANDARD_VERTICAL_RAIL_RTL" : "STANDARD_VERTICAL_RAIL";
                            boolean matched = false;
                            for (Object constVal : enumCls.getEnumConstants()) {
                                String name = ((Enum<?>) constVal).name();
                                if (targetEnumName.equals(name)) {
                                    args[3] = constVal;
                                    matched = true;
                                    break;
                                }
                            }
                            if (!matched) {
                                for (Object constVal : enumCls.getEnumConstants()) {
                                    String name = ((Enum<?>) constVal).name();
                                    if (name.contains("VERTICAL_RAIL") && (isRhd ? (name.contains("RHD") || name.contains("RTL")) : (name.contains("LHD") || !name.contains("RHD")))) {
                                        args[3] = constVal;
                                        break;
                                    }
                                }
                            }
                        } else if (typeArg instanceof Integer) {
                            args[3] = isRhd ? 4 : 3;
                        }

                        // 3. hasVerticalRail = true
                        if (args.length > 5) {
                            args[5] = true;
                        }

                        // 4. isDriverAlignedDashboard = false (VÔ CÙNG QUAN TRỌNG: Tắt Dashboard chia đôi màn hình!)
                        if (args.length > 7 && args[7] instanceof Boolean) {
                            args[7] = false;
                        }

                        // 5. isHeroLayout = false
                        if (args.length > 8 && args[8] instanceof Boolean) {
                            args[8] = false;
                        }

                        Log.i(TAG, "SUCCESS: Rewrote Coolwalk Layout to STANDARD_VERTICAL_RAIL (Fullscreen single-pane, no dashboard), targetResId=" + targetResId);
                    } catch (Throwable t) {
                        Log.e(TAG, "Error in layout rewrite hook", t);
                    }
                }
            };

            // 1. Hook trực tiếp class nsw (LayoutInfo trong Gearhead 17.6)
            try {
                Class<?> nswCls = Class.forName("nsw", false, lpparam.classLoader);
                for (Constructor<?> ctor : nswCls.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() >= 5) {
                        XposedBridge.hookMethod(ctor, layoutHook);
                        Log.i(TAG, "Hooked LayoutInfo constructor in nsw, params=" + ctor.getParameterCount());
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Hook nsw failed: " + t.getMessage());
            }

            // 2. Hook trực tiếp class nsy (fallback)
            try {
                Class<?> nsyCls = Class.forName("nsy", false, lpparam.classLoader);
                for (Constructor<?> ctor : nsyCls.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() >= 5) {
                        XposedBridge.hookMethod(ctor, layoutHook);
                        Log.i(TAG, "Hooked constructor in nsy, params=" + ctor.getParameterCount());
                    }
                }
            } catch (Throwable ignored) {
            }

            // 3. Hook thêm class LayoutInfo chuẩn nếu có
            try {
                XposedBridge.hookAllConstructors(Class.forName("com.google.android.apps.auto.components.system.layout.LayoutInfo", false, lpparam.classLoader), layoutHook);
            } catch (Throwable ignored) {
            }

        } catch (Throwable e) {
            Log.w(TAG, "hookCoolwalkLayout failed", e);
        }
    }

    private static void hookRailComponents(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook LayoutInflater.inflate để bắt ngay khi FacetBar vừa được inflate
        try {
            XposedBridge.hookAllMethods(LayoutInflater.class, "inflate", new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 1 && param.args[0] instanceof Integer) {
                        int resId = (Integer) param.args[0];
                        Object result = param.getResult();
                        if (result instanceof ViewGroup) {
                            ViewGroup vg = (ViewGroup) result;
                            try {
                                String entry = vg.getResources().getResourceEntryName(resId);
                                if ("gh_coolwalk_vertical_facet_bar".equals(entry)
                                        || "gh_coolwalk_facet_bar".equals(entry)
                                        || "gh_coolwalk_facet_bar_rhd".equals(entry)) {
                                    vg.post(() -> tryOverlayRail(vg));
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            });
            Log.i(TAG, "Hooked LayoutInflater.inflate for facet bar layouts");
        } catch (Throwable ignored) {
        }

        String[] candidateClasses = {
                "com.google.android.apps.auto.components.system.facetbar.widget.RailWidgetView",
                "com.google.android.apps.auto.components.coolwalk.rail.RailWidgetView",
                "com.google.android.apps.auto.components.system.facetbar.hotseat.RailHotseatItemView",
                "com.google.android.apps.auto.components.system.statusbar.RailStatusBarFragment"
        };

        for (String cName : candidateClasses) {
            try {
                Class<?> clazz = lpparam.classLoader.loadClass(cName);
                if (clazz == null) continue;

                if (cName.endsWith("Fragment")) {
                    XposedBridge.hookAllMethods(clazz, "onViewCreated", new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            View v = (View) param.args[0];
                            if (v != null) {
                                scheduleRailSearch(v);
                            }
                        }
                    });
                } else {
                    XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            final View v = (View) param.thisObject;
                            scheduleRailSearch(v);
                        }
                    });

                    try {
                        XposedBridge.hookAllMethods(clazz, "onAttachedToWindow", new XC_MethodHook() {
                            @Override
                            public void afterHookedMethod(MethodHookParam param) {
                                final View v = (View) param.thisObject;
                                scheduleRailSearch(v);
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
                Log.i(TAG, "Hooked " + cName + " for rail discovery");
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                Log.w(TAG, "Failed hooking " + cName + ": " + t.getMessage());
            }
        }
    }

    private static void scheduleRailSearch(final View view) {
        if (view == null) return;
        view.post(() -> {
            View root = view.getRootView();
            if (root == null || !sObservedRoots.add(root)) {
                return;
            }

            ViewGroup rail = findRail(root);
            if (rail != null) {
                tryOverlayRail(rail);
                return;
            }

            final android.view.ViewTreeObserver vto = root.getViewTreeObserver();
            if (vto == null || !vto.isAlive()) return;

            final java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger(0);
            vto.addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    ViewGroup foundRail = findRail(root);
                    if (foundRail != null || attempts.incrementAndGet() > 30) {
                        try {
                            if (vto.isAlive()) {
                                vto.removeOnGlobalLayoutListener(this);
                            }
                        } catch (Throwable ignored) {
                        }
                        if (foundRail != null) {
                            tryOverlayRail(foundRail);
                        }
                    }
                }
            });
        });
    }

    private static ViewGroup findRail(View node) {
        if (node == null) return null;
        try {
            int id = node.getId();
            if (id != View.NO_ID && node.getResources() != null) {
                String entry = node.getResources().getResourceEntryName(id);
                if ("rail".equals(entry) && node instanceof ViewGroup) {
                    return (ViewGroup) node;
                }
            }
        } catch (Throwable ignored) {
        }

        if (node instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) node;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                ViewGroup res = findRail(vg.getChildAt(i));
                if (res != null) return res;
            }
        }
        return null;
    }

    private static View findDirectChildContainingPattern(ViewGroup parent, String... patterns) {
        if (parent == null) return null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (containsPattern(child, patterns)) {
                return child;
            }
        }
        return null;
    }

    private static boolean containsPattern(View v, String... patterns) {
        if (v == null) return false;
        try {
            int id = v.getId();
            if (id != View.NO_ID && v.getResources() != null) {
                String entry = v.getResources().getResourceEntryName(id).toLowerCase();
                for (String p : patterns) {
                    if (entry.contains(p.toLowerCase())) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (containsPattern(vg.getChildAt(i), patterns)) return true;
            }
        }
        return false;
    }

    private static volatile View sDrawerBtnView = null;
    private static volatile boolean sReceiverRegistered = false;

    private static void registerDrawerReceiver(Context context) {
        if (sReceiverRegistered || context == null) return;
        synchronized (AaCoolwalkUiHook.class) {
            if (sReceiverRegistered) return;
            try {
                Context appContext = context.getApplicationContext();
                if (appContext == null) appContext = context;
                android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_OPEN_AA_DRAWER);
                android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        Log.i(TAG, "Received ACTION_OPEN_AA_DRAWER -> Triggering Android Auto App Drawer");
                        handleHomeClick(ctx);
                    }
                };
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    appContext.registerReceiver(receiver, filter);
                }
                sReceiverRegistered = true;
                Log.i(TAG, "Registered ACTION_OPEN_AA_DRAWER receiver successfully");
            } catch (Throwable t) {
                Log.w(TAG, "registerDrawerReceiver failed: " + t.getMessage());
            }
        }
    }

    private static synchronized void tryOverlayRail(final ViewGroup rail) {
        if (rail == null || !sInjectedRails.add(rail)) {
            return;
        }

        registerDrawerReceiver(rail.getContext());

        if (rail.findViewWithTag(NAVBAR_TAG) != null) {
            return;
        }

        rail.post(() -> {
            try {
                Context context = rail.getContext();
                registerDrawerReceiver(context);
                float density = context.getResources().getDisplayMetrics().density;

                // 1. Tìm các view con cần giữ lại:
                // - Status Bar (đồng hồ, sóng, pin)
                // - Nút Assistant (mic / voice)
                // - Nút danh sách app của Android Auto (ô vuông chia đôi / launcher / dashboard)
                // - Hotseat (danh sách icon app tròn)
                View statusBarView = findDirectChildContainingPattern(rail, "status_bar");
                View assistantBtnView = findDirectChildContainingPattern(rail, "assistant", "mic", "voice");
                View drawerBtnView = findDirectChildContainingPattern(rail, "launcher", "dashboard", "drawer");
                View hotseatView = findDirectChildContainingPattern(rail, "hotseat");

                if (drawerBtnView != null) {
                    sDrawerBtnView = drawerBtnView;
                }

                if (statusBarView != null) rail.removeView(statusBarView);
                if (assistantBtnView != null) rail.removeView(assistantBtnView);
                if (drawerBtnView != null) rail.removeView(drawerBtnView);
                if (hotseatView != null) rail.removeView(hotseatView);

                // 2. Dọn sạch các view thừa còn lại (widget media rác) để tránh đè nút
                rail.removeAllViews();

                // 3. Tạo layout điều hướng chuẩn mới
                LinearLayout facetBar = new LinearLayout(context);
                facetBar.setTag(NAVBAR_TAG);
                facetBar.setOrientation(LinearLayout.VERTICAL);
                facetBar.setGravity(Gravity.CENTER_HORIZONTAL);
                facetBar.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                facetBar.setBackgroundColor(Color.BLACK);

                // 3.1. Đỉnh: Status Bar gốc (giờ, pin, sóng)
                if (statusBarView != null) {
                    ViewGroup.LayoutParams slp = statusBarView.getLayoutParams();
                    if (slp == null) {
                        slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    }
                    facetBar.addView(statusBarView, slp);
                }

                // 3.2. Nút Assistant ngay trên nút mở ứng dụng của Android Auto
                if (assistantBtnView != null) {
                    LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    alp.gravity = Gravity.CENTER_HORIZONTAL;
                    alp.topMargin = (int) (6 * density + 0.5f);
                    alp.bottomMargin = (int) (4 * density + 0.5f);
                    facetBar.addView(assistantBtnView, alp);
                }

                // 3.3. Nút danh sách app của Android Auto (ô vuông chia đôi)
                if (drawerBtnView != null) {
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    dlp.gravity = Gravity.CENTER_HORIZONTAL;
                    dlp.topMargin = (int) (4 * density + 0.5f);
                    dlp.bottomMargin = (int) (4 * density + 0.5f);
                    facetBar.addView(drawerBtnView, dlp);
                }

                // 3.4. Spacer 1 giãn cách đều phía trên hotseat
                View spacer1 = new View(context);
                facetBar.addView(spacer1, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f
                ));

                // 3.5. Giữa: Danh sách app (hotseat: Maps, Nhạc, Điện thoại, AntScreen...) GIỮ NGUYÊN
                if (hotseatView != null) {
                    LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    hlp.gravity = Gravity.CENTER_HORIZONTAL;
                    facetBar.addView(hotseatView, hlp);
                }

                // 3.6. Spacer 2 giãn cách đều phía dưới hotseat
                View spacer2 = new View(context);
                facetBar.addView(spacer2, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f
                ));

                // 3.7. Đáy: Cụm 3 nút điều hướng phong cách nguyên bản Android Auto (không nền xanh)
                LinearLayout navButtons = buildNavLayout(context, density);
                LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                navLp.gravity = Gravity.CENTER_HORIZONTAL;
                navLp.bottomMargin = (int) (16 * density + 0.5f);
                facetBar.addView(navButtons, navLp);

                // Vô hiệu hóa clip
                ViewParent p = rail;
                while (p instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) p;
                    vg.setClipChildren(false);
                    vg.setClipToPadding(false);
                    p = p.getParent();
                }

                rail.addView(facetBar);
                facetBar.bringToFront();
                rail.bringChildToFront(facetBar);
                Log.i(TAG, "SUCCESS: Organized rail: Status -> Assistant -> AppDrawer -> Hotseat -> 3 Nav Buttons");
            } catch (Throwable e) {
                Log.e(TAG, "Failed organizing rail facet bar", e);
            }
        });
    }

    private static LinearLayout buildNavLayout(Context context, float density) {
        LinearLayout navBar = new LinearLayout(context);
        navBar.setTag(NAVBAR_TAG);
        navBar.setOrientation(LinearLayout.VERTICAL);
        navBar.setGravity(Gravity.CENTER_HORIZONTAL);
        navBar.setPadding(0, 0, 0, 0);

        // Nền trong suốt, không dùng nền xanh
        navBar.setBackgroundColor(Color.TRANSPARENT);

        // 1. Nút Trở về (Back - Keycode 4)
        navBar.addView(createNavButton(context, "‹", 26, 4));
        // 2. Nút Trang chủ (Home - Keycode 3) -> Mở App Drawer Android Auto
        navBar.addView(createNavButton(context, "⌂", 21, 3));
        // 3. Nút Ứng dụng gần đây (Recents - Keycode 187)
        navBar.addView(createNavButton(context, "≡", 22, 187));

        return navBar;
    }

    private static View createNavButton(Context context, String symbol, int textSizeSp, int keyCode) {
        TextView tv = new TextView(context);
        tv.setText(symbol);
        tv.setTextSize(textSizeSp);
        tv.setTextColor(Color.WHITE);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);

        float density = context.getResources().getDisplayMetrics().density;
        int widthPx = (int) (52 * density + 0.5f);
        int heightPx = (int) (42 * density + 0.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(widthPx, heightPx);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, (int) (3 * density), 0, (int) (3 * density));
        tv.setLayoutParams(lp);

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(Color.TRANSPARENT);

        // hiệu ứng khi bấm nút
        GradientDrawable pressedBg = new GradientDrawable();
        pressedBg.setColor(Color.parseColor("#33FFFFFF"));
        pressedBg.setCornerRadius(21 * density);

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, pressedBg);
        sld.addState(new int[]{}, normalBg);
        tv.setBackground(sld);

        tv.setClickable(true);
        tv.setFocusable(true);

        if (keyCode == 3) {
            tv.setOnClickListener(v -> handleHomeClick(context));
        } else {
            tv.setOnClickListener(v -> sendControl(context, keyCode));
        }
        return tv;
    }

    private static void handleHomeClick(Context context) {
        try {
            // 1. Gửi signal sang AntScreen để đóng Recents Overlay nếu đang mở
            sendControl(context, 3);

            // 2. Kích hoạt mở App Drawer của Android Auto
            View drawerBtn = sDrawerBtnView;
            if (drawerBtn == null) {
                for (ViewGroup rail : sInjectedRails) {
                    drawerBtn = findDirectChildContainingPattern(rail, "launcher", "dashboard", "drawer");
                    if (drawerBtn != null) {
                        sDrawerBtnView = drawerBtn;
                        break;
                    }
                }
            }

            if (drawerBtn != null) {
                boolean isAlreadyOpen = isDrawerOpen(drawerBtn);
                if (isAlreadyOpen) {
                    Log.i(TAG, "Android Auto App Drawer is already open -> Keeping it open (prevent Maps toggle)");
                    return;
                }

                Log.i(TAG, "Clicking drawer button to open Android Auto App Drawer");
                performClickDeep(drawerBtn);
            } else {
                Log.w(TAG, "sDrawerBtnView is null, cannot open Android Auto App Drawer directly");
            }
        } catch (Throwable e) {
            Log.e(TAG, "handleHomeClick failed", e);
        }
    }

    private static boolean isDrawerOpen(View v) {
        if (v == null) return false;
        if (v.isSelected() || v.isActivated()) return true;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (isDrawerOpen(vg.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static void performClickDeep(View v) {
        if (v == null) return;
        v.post(() -> {
            try {
                if (v.performClick()) return;
                if (v instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) v;
                    for (int i = 0; i < vg.getChildCount(); i++) {
                        View child = vg.getChildAt(i);
                        if (child.isClickable() && child.performClick()) return;
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "performClickDeep failed", t);
            }
        });
    }

    private static void sendControl(Context context, int keyCode) {
        try {
            Intent intent = new Intent(ACTION_SCREEN_CONTROL);
            intent.setPackage(TARGET_PKG);
            intent.putExtra(EXTRA_ACTION, keyCode);
            context.sendBroadcast(intent);
            Log.d(TAG, "Sent control intent: keyCode=" + keyCode);
        } catch (Throwable e) {
            Log.e(TAG, "sendControl broadcast failed: " + keyCode, e);
        }
    }
}
