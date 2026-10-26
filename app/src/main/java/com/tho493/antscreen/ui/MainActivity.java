package com.tho493.antscreen.ui;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.tho493.antscreen.R;
import com.tho493.antscreen.engine.AppManager;
import com.tho493.antscreen.engine.DexBlobGenerator;
import com.tho493.antscreen.engine.MirrorEngineDeployer;
import com.tho493.antscreen.engine.SettingsManager;
import com.tho493.antscreen.ipc.MirrorManagerClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "AntScreen_Main";
    private static final String REQUIRED_GEARHEAD_VERSION = "17.6";
    private static final String GEARHEAD_PACKAGE = "com.google.android.projection.gearhead";
    private static final String GEARHEAD_APK_PATH = "/data/adb/antscreen/gearhead_17.6.apk";
    private static final String GEARHEAD_APK_TMP = "/data/local/tmp/gearhead_17.6.apk";
    public static final int MAX_ALLOWED_APPS = AppManager.MAX_SLOTS;

    // Tabs views
    private View mTabHomeView;
    private View mTabAppsView;
    private View mTabSettingsView;

    // Bottom Navigation Bar
    private View mNavTabHome;
    private View mNavTabApps;
    private View mNavTabSettings;
    private FrameLayout mPillTabHome;
    private FrameLayout mPillTabApps;
    private FrameLayout mPillTabSettings;
    private ImageView mIvTabHome;
    private ImageView mIvTabApps;
    private ImageView mIvTabSettings;
    private TextView mTvTabHome;
    private TextView mTvTabApps;
    private TextView mTvTabSettings;

    // Tab 1 Status Cards & Banner
    private LinearLayout mBannerStatus;
    private FrameLayout mBannerIconContainer;
    private ImageView mIvBannerIcon;
    private TextView mTvBannerTitle;
    private TextView mTvBannerSubtitle;

    private LinearLayout mCardSystem;
    private FrameLayout mContainerSystemIcon;
    private ImageView mIvSystemIcon;
    private TextView mTvSystemSub;

    private LinearLayout mCardGms;
    private FrameLayout mContainerGmsIcon;
    private ImageView mIvGmsIcon;
    private TextView mTvGmsSub;

    private LinearLayout mCardAa;
    private FrameLayout mContainerAaIcon;
    private ImageView mIvAaIcon;
    private TextView mTvAaSub;

    private LinearLayout mCardEngine;
    private FrameLayout mContainerEngineIcon;
    private ImageView mIvEngineIcon;
    private TextView mTvEngineSub;

    // Tab 2 Apps
    private TextView mTvAppsCountBadge;
    private ProgressBar mPbAppsLimit;
    private EditText mEtSearchApps;
    private RecyclerView mRvAppsTab;
    private ProgressBar mPbAppsLoading;
    private AppAdapter mAppAdapter;
    private final List<AppManager.AppItem> mAllApps = new ArrayList<>();
    private final List<AppManager.AppItem> mFilteredApps = new ArrayList<>();

    // Tab 3 Settings Dynamic Status
    private FrameLayout mContainerRedeployIcon;
    private ImageView mIvRedeployIcon;
    private TextView mTvRedeploySub;
    private FrameLayout mContainerAaSettingsIcon;
    private ImageView mIvAaSettingsIcon;

    // Status Cache
    private boolean mSystemOk = true;
    private boolean mGmsOk = false;
    private boolean mAaOk = false;
    private boolean mEngineOk = false;
    private String mCurrentAaVersion = "Chưa cài đặt";

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupNavigation();
        setupTab1ClickListeners();
        setupTab2Apps();
        setupTab3Settings();

        // Khởi tạo Binder client và kiểm tra trạng thái
        MirrorManagerClient.init(this, () -> runOnUiThread(this::refreshStatus));
        refreshStatus();

        // Đồng bộ ngầm slots và tạo dexblob
        AppManager.syncComponentState(this);
        new Thread(() -> DexBlobGenerator.generate(this)).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void initViews() {
        // Tab Containers
        mTabHomeView = findViewById(R.id.tab_home_view);
        mTabAppsView = findViewById(R.id.tab_apps_view);
        mTabSettingsView = findViewById(R.id.tab_settings_view);

        // Bottom Nav
        mNavTabHome = findViewById(R.id.nav_tab_home);
        mNavTabApps = findViewById(R.id.nav_tab_apps);
        mNavTabSettings = findViewById(R.id.nav_tab_settings);
        mPillTabHome = findViewById(R.id.pill_tab_home);
        mPillTabApps = findViewById(R.id.pill_tab_apps);
        mPillTabSettings = findViewById(R.id.pill_tab_settings);
        mIvTabHome = findViewById(R.id.iv_tab_home);
        mIvTabApps = findViewById(R.id.iv_tab_apps);
        mIvTabSettings = findViewById(R.id.iv_tab_settings);
        mTvTabHome = findViewById(R.id.tv_tab_home);
        mTvTabApps = findViewById(R.id.tv_tab_apps);
        mTvTabSettings = findViewById(R.id.tv_tab_settings);

        // Banner
        mBannerStatus = findViewById(R.id.banner_status);
        mBannerIconContainer = findViewById(R.id.banner_icon_container);
        mIvBannerIcon = findViewById(R.id.iv_banner_icon);
        mTvBannerTitle = findViewById(R.id.tv_banner_title);
        mTvBannerSubtitle = findViewById(R.id.tv_banner_subtitle);

        // Cards
        mCardSystem = findViewById(R.id.card_system_version);
        mContainerSystemIcon = findViewById(R.id.container_system_icon);
        mIvSystemIcon = findViewById(R.id.iv_system_icon);
        mTvSystemSub = findViewById(R.id.tv_system_version_sub);

        mCardGms = findViewById(R.id.card_play_services);
        mContainerGmsIcon = findViewById(R.id.container_gms_icon);
        mIvGmsIcon = findViewById(R.id.iv_gms_icon);
        mTvGmsSub = findViewById(R.id.tv_gms_sub);

        mCardAa = findViewById(R.id.card_android_auto);
        mContainerAaIcon = findViewById(R.id.container_aa_icon);
        mIvAaIcon = findViewById(R.id.iv_aa_icon);
        mTvAaSub = findViewById(R.id.tv_aa_sub);

        mCardEngine = findViewById(R.id.card_mirror_engine);
        mContainerEngineIcon = findViewById(R.id.container_engine_icon);
        mIvEngineIcon = findViewById(R.id.iv_engine_icon);
        mTvEngineSub = findViewById(R.id.tv_engine_sub);

        // Tab 2 Apps
        mTvAppsCountBadge = findViewById(R.id.tv_apps_count_badge);
        mPbAppsLimit = findViewById(R.id.pb_apps_limit);
        mEtSearchApps = findViewById(R.id.et_search_apps);
        mRvAppsTab = findViewById(R.id.rv_apps_tab);
        mPbAppsLoading = findViewById(R.id.pb_apps_loading);

        // Tab 3 Settings
        mContainerRedeployIcon = findViewById(R.id.container_redeploy_icon);
        mIvRedeployIcon = findViewById(R.id.iv_redeploy_icon);
        mTvRedeploySub = findViewById(R.id.tv_redeploy_sub);
        mContainerAaSettingsIcon = findViewById(R.id.container_aa_settings_icon);
        mIvAaSettingsIcon = findViewById(R.id.iv_aa_settings_icon);
    }

    private void setupNavigation() {
        mNavTabHome.setOnClickListener(v -> selectTab(0));
        mNavTabApps.setOnClickListener(v -> selectTab(1));
        mNavTabSettings.setOnClickListener(v -> selectTab(2));
    }

    private void selectTab(int index) {
        mTabHomeView.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        mTabAppsView.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        mTabSettingsView.setVisibility(index == 2 ? View.VISIBLE : View.GONE);

        // Update Nav visual
        updateTabVisual(mPillTabHome, mIvTabHome, mTvTabHome, index == 0);
        updateTabVisual(mPillTabApps, mIvTabApps, mTvTabApps, index == 1);
        updateTabVisual(mPillTabSettings, mIvTabSettings, mTvTabSettings, index == 2);

        if (index == 1 && mAllApps.isEmpty()) {
            loadAppsForTab();
        }
    }

    private void updateTabVisual(FrameLayout pill, ImageView iv, TextView tv, boolean selected) {
        if (selected) {
            pill.setBackgroundResource(R.drawable.bg_pill_selected);
            iv.setColorFilter(Color.parseColor("#1C1917"));
            tv.setTextColor(Color.parseColor("#1C1917"));
            tv.setTypeface(null, Typeface.BOLD);
        } else {
            pill.setBackground(null);
            iv.setColorFilter(Color.parseColor("#78716C"));
            tv.setTextColor(Color.parseColor("#78716C"));
            tv.setTypeface(null, Typeface.NORMAL);
        }
    }

    // ================= TAB 1: TRẠNG THÁI =================
    private void refreshStatus() {
        int attentionItems = 0;

        // 1. System Version
        String release = Build.VERSION.RELEASE;
        int sdk = Build.VERSION.SDK_INT;
        mTvSystemSub.setText("Android " + release + " (API " + sdk + ")");
        mSystemOk = (sdk >= 32);
        setItemIconStatus(mContainerSystemIcon, mIvSystemIcon, mSystemOk);
        if (!mSystemOk) attentionItems++;

        // 2. Google Play Services
        mGmsOk = isPackageInstalled("com.google.android.gms");
        mTvGmsSub.setText(mGmsOk ? "Detected" : "Chưa cài đặt");
        setItemIconStatus(mContainerGmsIcon, mIvGmsIcon, mGmsOk);
        if (!mGmsOk) attentionItems++;

        // 3. Android Auto (Buộc phiên bản 17.6)
        checkAndroidAutoVersion();
        if (!mAaOk) attentionItems++;

        // 4. Mirror Engine
        mEngineOk = (MirrorManagerClient.getService() != null);
        mTvEngineSub.setText(mEngineOk ? "Active" : "Not deployed");
        setItemIconStatus(mContainerEngineIcon, mIvEngineIcon, mEngineOk);
        if (!mEngineOk) attentionItems++;

        // Update Banner
        if (attentionItems > 0) {
            mBannerStatus.setBackgroundResource(R.drawable.bg_banner_warning);
            mBannerIconContainer.setBackgroundResource(R.drawable.bg_circle_icon_warning);
            mIvBannerIcon.setImageResource(R.drawable.ic_exclamation);
            mTvBannerTitle.setText("Setup needed");
            mTvBannerTitle.setTextColor(Color.parseColor("#B45309"));
            mTvBannerSubtitle.setText(attentionItems + " items need attention — see below");
        } else {
            mBannerStatus.setBackgroundResource(R.drawable.bg_banner_success);
            mBannerIconContainer.setBackgroundResource(R.drawable.bg_circle_icon_check);
            mIvBannerIcon.setImageResource(R.drawable.ic_check);
            mTvBannerTitle.setText("All good");
            mTvBannerTitle.setTextColor(Color.parseColor("#15803D"));
            mTvBannerSubtitle.setText("Tất cả đã sẵn sàng kết nối Android Auto");
        }

        // Cập nhật trạng thái động cho Tab 3 Cài đặt
        if (mContainerRedeployIcon != null && mIvRedeployIcon != null && mTvRedeploySub != null) {
            setItemIconStatus(mContainerRedeployIcon, mIvRedeployIcon, mEngineOk);
            if (mEngineOk) {
                mTvRedeploySub.setText("Đang hoạt động · Nhấn để quản lý");
            } else {
                mTvRedeploySub.setText("Chưa kích hoạt · Nhấn để triển khai");
                new Thread(() -> {
                    boolean deployed = MirrorEngineDeployer.isEngineDeployed();
                    runOnUiThread(() -> {
                        if (!mEngineOk && mTvRedeploySub != null) {
                            if (deployed) {
                                mTvRedeploySub.setText("Đã cài trong Magisk · Cần khởi động lại framework");
                            } else {
                                mTvRedeploySub.setText("Chưa cài đặt · Nhấn để triển khai");
                            }
                        }
                    });
                }).start();
            }
        }
        if (mContainerAaSettingsIcon != null && mIvAaSettingsIcon != null) {
            setItemIconStatus(mContainerAaSettingsIcon, mIvAaSettingsIcon, mAaOk);
        }
    }

    private void checkAndroidAutoVersion() {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo(GEARHEAD_PACKAGE, 0);
            mCurrentAaVersion = pi.versionName;
            mAaOk = ConditionDetailActivity.isAaCompatible(mCurrentAaVersion);
            mTvAaSub.setText(mCurrentAaVersion.split("-")[0] + " · " + (mAaOk ? "Tương thích" : "Không tương thích"));
        } catch (PackageManager.NameNotFoundException e) {
            mAaOk = false;
            mCurrentAaVersion = "Chưa cài đặt";
            mTvAaSub.setText("Chưa cài đặt · Không tương thích");
        }
        setItemIconStatus(mContainerAaIcon, mIvAaIcon, mAaOk);
    }

    private void setItemIconStatus(FrameLayout container, ImageView icon, boolean ok) {
        if (ok) {
            container.setBackgroundResource(R.drawable.bg_circle_icon_check);
            icon.setImageResource(R.drawable.ic_check);
        } else {
            container.setBackgroundResource(R.drawable.bg_circle_icon_warning);
            icon.setImageResource(R.drawable.ic_exclamation);
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void setupTab1ClickListeners() {
        mCardSystem.setOnClickListener(v -> openConditionDetail(ConditionDetailActivity.TYPE_SYSTEM));
        mCardGms.setOnClickListener(v -> openConditionDetail(ConditionDetailActivity.TYPE_GMS));
        mCardAa.setOnClickListener(v -> openConditionDetail(ConditionDetailActivity.TYPE_ANDROID_AUTO));
        mCardEngine.setOnClickListener(v -> openConditionDetail(ConditionDetailActivity.TYPE_MIRROR_ENGINE));
    }

    private void openConditionDetail(int conditionType) {
        Intent intent = new Intent(this, ConditionDetailActivity.class);
        intent.putExtra(ConditionDetailActivity.EXTRA_TYPE, conditionType);
        startActivity(intent);
    }

    // CHI TIẾT ANDROID AUTO & NÚT SỬA LẠI PHIÊN BẢN
    private void showAndroidAutoDetailDialog() {
        String msg = "Gói: " + GEARHEAD_PACKAGE + "\n"
                + "Phiên bản hiện tại: " + mCurrentAaVersion + "\n"
                + "Phiên bản yêu cầu: " + REQUIRED_GEARHEAD_VERSION + " (17.6.663424-release)\n"
                + "Trạng thái: " + (mAaOk ? "Hợp lệ" : "Không tương thích / Cần cài đặt lại") + "\n\n"
                + "Android Auto buộc phải sử dụng phiên bản 17.6 chuẩn để đảm bảo tính năng phản chiếu và không bị chặn.";

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Chi tiết Android Auto")
                .setMessage(msg)
                .setPositiveButton("Đóng", null);

        builder.setNeutralButton("Sửa lại phiên bản", (dialog, which) -> {
            promptReinstallGearhead();
        });

        builder.show();
    }

    private void promptReinstallGearhead() {
        new AlertDialog.Builder(this)
                .setTitle("Cài đặt lại Android Auto 17.6")
                .setMessage("Ứng dụng sẽ tự động cài đặt Android Auto phiên bản 17.6 chuẩn qua quyền Root.\nBạn có muốn tiếp tục?")
                .setPositiveButton("Cài đặt ngay", (dialog, which) -> installRequiredGearhead())
                .setNegativeButton("Hủy", null)
                .show();
    }

    private void installRequiredGearhead() {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("Đang cài đặt Android Auto");
        progress.setMessage("Đang nạp file APK và cài đặt phiên bản 17.6...");
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            boolean success = false;
            String outputMsg = "";

            // Kiểm tra các đường dẫn có thể có của APK
            File targetApk = new File(GEARHEAD_APK_PATH);
            if (!targetApk.exists()) {
                targetApk = new File(GEARHEAD_APK_TMP);
            }

            if (!targetApk.exists()) {
                outputMsg = "Không tìm thấy file APK chuẩn tại " + GEARHEAD_APK_PATH;
            } else {
                try {
                    String cmd = "pm install -r -d " + targetApk.getAbsolutePath();
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                    BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                    while ((line = errReader.readLine()) != null) sb.append(line).append("\n");
                    int exit = p.waitFor();
                    success = (exit == 0 && sb.toString().contains("Success"));
                    outputMsg = sb.toString();
                } catch (Throwable e) {
                    outputMsg = e.getMessage();
                }
            }

            final boolean finalSuccess = success;
            final String finalMsg = outputMsg;
            runOnUiThread(() -> {
                progress.dismiss();
                if (finalSuccess) {
                    Toast.makeText(this, "Đã cài đặt thành công Android Auto 17.6!", Toast.LENGTH_LONG).show();
                    refreshStatus();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Cài đặt thất bại")
                            .setMessage("Chi tiết: " + finalMsg)
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }).start();
    }

    // ================= TAB 2: TÙY CHỈNH CÁC APP HIỂN THỊ =================
    private void setupTab2Apps() {
        mRvAppsTab.setLayoutManager(new LinearLayoutManager(this));
        mAppAdapter = new AppAdapter();
        mRvAppsTab.setAdapter(mAppAdapter);

        mEtSearchApps.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        updateAppsLimitBadge();
    }

    private void loadAppsForTab() {
        mPbAppsLoading.setVisibility(View.VISIBLE);
        mRvAppsTab.setVisibility(View.GONE);

        new Thread(() -> {
            List<AppManager.AppItem> apps = AppManager.getInstalledApps(this);
            runOnUiThread(() -> {
                mAllApps.clear();
                mAllApps.addAll(apps);
                filterApps(mEtSearchApps.getText().toString());
                mPbAppsLoading.setVisibility(View.GONE);
                mRvAppsTab.setVisibility(View.VISIBLE);
                updateAppsLimitBadge();
            });
        }).start();
    }

    private void filterApps(String query) {
        mFilteredApps.clear();
        String q = (query != null) ? query.trim().toLowerCase(Locale.ROOT) : "";
        for (AppManager.AppItem item : mAllApps) {
            if (q.isEmpty() || item.appName.toLowerCase(Locale.ROOT).contains(q)
                    || item.packageName.toLowerCase(Locale.ROOT).contains(q)) {
                mFilteredApps.add(item);
            }
        }
        mAppAdapter.notifyDataSetChanged();
    }

    private void updateAppsLimitBadge() {
        int enabledCount = 0;
        for (AppManager.AppItem item : mAllApps) {
            if (item.isEnabled) enabledCount++;
        }
        mTvAppsCountBadge.setText(enabledCount + " / " + MAX_ALLOWED_APPS + " đã bật");
        mPbAppsLimit.setMax(MAX_ALLOWED_APPS);
        mPbAppsLimit.setProgress(enabledCount);

        if (enabledCount >= MAX_ALLOWED_APPS) {
            mTvAppsCountBadge.setTextColor(Color.parseColor("#DC2626"));
        } else {
            mTvAppsCountBadge.setTextColor(Color.parseColor("#B45309"));
        }
    }

    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_list, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppManager.AppItem item = mFilteredApps.get(position);
            holder.tvName.setText(item.appName);
            holder.tvPackage.setText(item.packageName);
            holder.ivIcon.setImageDrawable(item.icon);

            holder.switchEnable.setOnCheckedChangeListener(null);
            holder.switchEnable.setChecked(item.isEnabled);
            holder.switchEnable.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked) {
                    // Kiểm tra giới hạn số app
                    int currentCount = 0;
                    for (AppManager.AppItem it : mAllApps) {
                        if (it.isEnabled) currentCount++;
                    }
                    if (currentCount >= MAX_ALLOWED_APPS) {
                        btn.setChecked(false);
                        Toast.makeText(MainActivity.this,
                                "Đã đạt giới hạn tối đa " + MAX_ALLOWED_APPS + " ứng dụng trên xe. Vui lòng tắt bớt ứng dụng khác trước.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                item.isEnabled = isChecked;
                AppManager.setPackageInjected(MainActivity.this, item.packageName, isChecked);
                updateAppsLimitBadge();
            });

            holder.itemView.setOnClickListener(v -> holder.switchEnable.toggle());
        }

        @Override
        public int getItemCount() {
            return mFilteredApps.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView tvName;
            TextView tvPackage;
            SwitchMaterial switchEnable;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.iv_app_icon);
                tvName = itemView.findViewById(R.id.tv_app_name);
                tvPackage = itemView.findViewById(R.id.tv_package_name);
                switchEnable = itemView.findViewById(R.id.switch_enable);
            }
        }
    }

    // ================= TAB 3: CÀI ĐẶT =================
    private void setupTab3Settings() {
        SwitchMaterial swFullscreen = findViewById(R.id.sw_fullscreen_mode);
        if (swFullscreen != null) {
            swFullscreen.setChecked(SettingsManager.isFullscreenMode(this));
            swFullscreen.setOnCheckedChangeListener((buttonView, isChecked) -> {
                SettingsManager.setFullscreenMode(this, isChecked);
                new Thread(() -> {
                    try {
                        Runtime.getRuntime().exec(new String[]{"su", "-c", "pkill -9 -f com.google.android.projection.gearhead"}).waitFor();
                    } catch (Throwable ignored) {
                    }
                }).start();
                Toast.makeText(this, isChecked
                        ? "Đã bật: Toàn màn hình (Đang làm mới Android Auto...)"
                        : "Đã tắt: Chia đôi màn hình (Đang làm mới Android Auto...)",
                        Toast.LENGTH_SHORT).show();
            });
            View cardFullscreen = findViewById(R.id.card_fullscreen_setting);
            if (cardFullscreen != null) {
                cardFullscreen.setOnClickListener(v -> swFullscreen.toggle());
            }
        }

        findViewById(R.id.btn_open_aa_settings).setOnClickListener(v -> {
            try {
                Intent intent = new Intent();
                intent.setClassName("com.google.android.projection.gearhead",
                        "com.google.android.projection.gearhead.companion.settings.LauncherAppSettingsActivity");
                startActivity(intent);
            } catch (Throwable e) {
                // Fallback bằng root nếu bị security exception
                new Thread(() -> {
                    try {
                        Runtime.getRuntime().exec(new String[]{"su", "-c",
                                "am start -n com.google.android.projection.gearhead/.companion.settings.LauncherAppSettingsActivity"}).waitFor();
                    } catch (Throwable ignored) {
                    }
                }).start();
            }
        });

        findViewById(R.id.btn_restart_framework).setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Khởi động lại thiết bị")
                    .setMessage("Hệ thống sẽ khởi động lại toàn bộ điện thoại để nạp đầy đủ các module Magisk và Zygisk.")
                    .setPositiveButton("Khởi động lại", (dialog, which) -> {
                        MirrorEngineDeployer.rebootDevice(null);
                    })
                    .setNegativeButton("Hủy", null)
                    .show();
        });

        findViewById(R.id.btn_redeploy_module).setOnClickListener(v -> {
            openConditionDetail(ConditionDetailActivity.TYPE_MIRROR_ENGINE);
        });
    }
}
