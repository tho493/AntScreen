package com.tho493.antscreen.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.tho493.antscreen.R;
import com.tho493.antscreen.engine.GearheadInstaller;
import com.tho493.antscreen.engine.MirrorEngineDeployer;
import com.tho493.antscreen.ipc.MirrorManagerClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class ConditionDetailActivity extends AppCompatActivity {
    public static final String EXTRA_TYPE = "extra_condition_type";
    public static final int TYPE_SYSTEM = 1;
    public static final int TYPE_GMS = 2;
    public static final int TYPE_ANDROID_AUTO = 3;
    public static final int TYPE_MIRROR_ENGINE = 4;

    private static final String GEARHEAD_PACKAGE = "com.google.android.projection.gearhead";
    private static final String REQUIRED_GEARHEAD_VERSION = "17.6";

    public static final String APKMIRROR_AA_URL =
            "https://www.apkmirror.com/apk/google-inc/android-auto/android-auto-17-6-6634-release/";

    private int mType = TYPE_SYSTEM;

    // Views
    private TextView mTvDetailTitle;
    private TextView mTvDetailCategory;
    private LinearLayout mBannerDetailStatus;
    private FrameLayout mContainerDetailIcon;
    private ImageView mIvDetailStatusIcon;
    private TextView mTvStatusHeadline;
    private TextView mTvStatusDescription;
    private LinearLayout mCardParamsContainer;

    // Android Auto Views
    private LinearLayout mSectionAaActions;
    private MaterialButton mBtnOpenApkMirror;
    private LinearLayout mContainerAaProgress;
    private ProgressBar mPbAaDownload;
    private TextView mTvAaDownloadStatus;
    private MaterialButton mBtnPickLocalApk;

    // Mirror Engine Views
    private LinearLayout mSectionEngineActions;
    private MaterialButton mBtnDeployEngine;
    private LinearLayout mContainerEngineProgress;
    private ProgressBar mPbEngineDeploy;
    private TextView mTvEngineDeployStatus;
    private MaterialButton mBtnRestartFramework;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> mApkPickerLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_condition_detail);

        mType = getIntent().getIntExtra(EXTRA_TYPE, TYPE_SYSTEM);

        initViews();
        setupPicker();
        bindData();
    }

    private void initViews() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_refresh_detail).setOnClickListener(v -> {
            Toast.makeText(this, "Đang làm mới dữ liệu...", Toast.LENGTH_SHORT).show();
            bindData();
        });

        mTvDetailTitle = findViewById(R.id.tv_detail_title);
        mTvDetailCategory = findViewById(R.id.tv_detail_category);
        mBannerDetailStatus = findViewById(R.id.banner_detail_status);
        mContainerDetailIcon = findViewById(R.id.container_detail_icon);
        mIvDetailStatusIcon = findViewById(R.id.iv_detail_status_icon);
        mTvStatusHeadline = findViewById(R.id.tv_status_headline);
        mTvStatusDescription = findViewById(R.id.tv_status_description);
        mCardParamsContainer = findViewById(R.id.card_params_container);

        mSectionAaActions = findViewById(R.id.section_aa_actions);
        mBtnOpenApkMirror = findViewById(R.id.btn_open_apkmirror_aa);
        mContainerAaProgress = findViewById(R.id.container_aa_download_progress);
        mPbAaDownload = findViewById(R.id.pb_aa_download);
        mTvAaDownloadStatus = findViewById(R.id.tv_aa_download_status);
        mBtnPickLocalApk = findViewById(R.id.btn_pick_local_apk_aa);

        mSectionEngineActions = findViewById(R.id.section_engine_actions);
        mBtnDeployEngine = findViewById(R.id.btn_deploy_mirror_engine);
        mContainerEngineProgress = findViewById(R.id.container_engine_progress);
        mPbEngineDeploy = findViewById(R.id.pb_engine_deploy);
        mTvEngineDeployStatus = findViewById(R.id.tv_engine_deploy_status);
        mBtnRestartFramework = findViewById(R.id.btn_restart_framework);

        // Click listeners cho Android Auto
        mBtnOpenApkMirror.setOnClickListener(v -> openApkMirrorWeb());
        mBtnPickLocalApk.setOnClickListener(v -> {
            try {
                mApkPickerLauncher.launch("*/*");
            } catch (Throwable t) {
                Toast.makeText(this, "Không thể mở trình chọn tệp: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // Click listeners cho Mirror Engine
        mBtnDeployEngine.setOnClickListener(v -> deployMirrorEngineQuick());
        mBtnRestartFramework.setOnClickListener(v -> promptRestartSystemServer());
    }

    private void openApkMirrorWeb() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(APKMIRROR_AA_URL));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "Không thể mở trình duyệt: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void setupPicker() {
        mApkPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                copyAndInstallLocalApk(uri);
            }
        });
    }

    private void bindData() {
        mCardParamsContainer.removeAllViews();
        mSectionAaActions.setVisibility(View.GONE);
        mSectionEngineActions.setVisibility(View.GONE);

        switch (mType) {
            case TYPE_SYSTEM:
                renderSystemDetails();
                break;
            case TYPE_GMS:
                renderGmsDetails();
                break;
            case TYPE_ANDROID_AUTO:
                renderAndroidAutoDetails();
                break;
            case TYPE_MIRROR_ENGINE:
                renderMirrorEngineDetails();
                break;
        }
    }

    // 1. SYSTEM
    private void renderSystemDetails() {
        mTvDetailTitle.setText("System Version");
        mTvDetailCategory.setText("Hệ điều hành & Nền tảng thiết bị");

        boolean ok = (Build.VERSION.SDK_INT >= 32);
        setStatusBanner(ok,
                ok ? "Tương thích hoàn hảo" : "Phiên bản không được hỗ trợ",
                ok ? "Thiết bị đáp ứng đầy đủ điều kiện hệ thống (Android 12L đến 17)."
                   : "AntScreen yêu cầu tối thiểu Android 12L (API 32). Vui lòng cập nhật hệ điều hành.");

        addParamRow("Nhà sản xuất", Build.MANUFACTURER, true, null);
        addParamRow("Tên thiết bị / Model", Build.MODEL + " (" + Build.PRODUCT + ")", true, null);
        addParamRow("Phiên bản Android", "Android " + Build.VERSION.RELEASE, ok, "v" + Build.VERSION.RELEASE);
        addParamRow("Mã API SDK", String.valueOf(Build.VERSION.SDK_INT), ok, "API " + Build.VERSION.SDK_INT);
        addParamRow("Kiến trúc CPU (ABIs)", String.join(", ", Build.SUPPORTED_ABIS), true, Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : null);
        addParamRow("Bản vá bảo mật", Build.VERSION.SECURITY_PATCH, true, null);
        addParamRow("Loại bản dựng (Build Type)", Build.TYPE, true, null);
    }

    // 2. GMS
    private void renderGmsDetails() {
        mTvDetailTitle.setText("Google Play Services");
        mTvDetailCategory.setText("Dịch vụ Google & Hỗ trợ xe hơi");

        boolean installed = isPackageInstalled("com.google.android.gms");
        String version = "Không có";
        if (installed) {
            try {
                version = getPackageManager().getPackageInfo("com.google.android.gms", 0).versionName;
            } catch (Throwable ignored) {}
        }

        setStatusBanner(installed,
                installed ? "Đã cài đặt sẵn sàng" : "Thiếu dịch vụ Google Play",
                installed ? "Dịch vụ Google Play đã sẵn sàng để kết nối giao thức Android Auto."
                          : "Vui lòng cài đặt Google Play Services để sử dụng tính năng Android Auto.");

        addParamRow("Tên gói dịch vụ", "com.google.android.gms", installed, null);
        addParamRow("Phiên bản", version, installed, installed ? "Verified" : "Missing");
        addParamRow("Giao thức ô tô", "Hỗ trợ Car Service Projection", installed, null);
    }

    public static boolean isAaCompatible(String versionName) {
        if (versionName == null || versionName.trim().isEmpty()) return false;
        String lower = versionName.toLowerCase();
        if (lower.contains("stub")) return false;
        try {
            String clean = versionName.split("-")[0].trim();
            String[] parts = clean.split("\\.");
            if (parts.length >= 2) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                if (major > 16) return true;
                if (major == 16 && minor >= 7) return true;
            }
        } catch (Throwable ignored) {
            if (versionName.contains("16.7") || versionName.contains("16.8") || versionName.contains("16.9") || versionName.contains("17.")) return true;
        }
        return false;
    }

    // 3. ANDROID AUTO
    private void renderAndroidAutoDetails() {
        mTvDetailTitle.setText("Android Auto");
        mTvDetailCategory.setText("Giao thức kết nối & Ứng dụng Gearhead");
        mSectionAaActions.setVisibility(View.VISIBLE);

        boolean installed = isPackageInstalled(GEARHEAD_PACKAGE);
        String versionName = "Chưa cài đặt";
        boolean isCompatible = false;

        if (installed) {
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(GEARHEAD_PACKAGE, 0);
                versionName = pi.versionName != null ? pi.versionName : "Không rõ";
                isCompatible = isAaCompatible(versionName);
            } catch (Throwable ignored) {}
        }

        setStatusBanner(isCompatible,
                isCompatible ? "Tương thích" : "Không tương thích",
                isCompatible ? "Phiên bản Android Auto tương thích với hệ thống."
                             : "Phiên bản Android Auto hiện tại không tương thích (yêu cầu từ v16.7 đến 17.6+).");

        addParamRow("Tên gói ứng dụng", GEARHEAD_PACKAGE, installed, null);
        addParamRow("Phiên bản hiện tại", versionName, isCompatible, isCompatible ? "Tương thích" : "Không tương thích");
        addParamRow("Trạng thái", isCompatible ? "Tương thích" : "Không tương thích", isCompatible, null);

        if (isCompatible) {
            mBtnPickLocalApk.setText("Cài đặt lại file APK Android Auto");
        } else {
            mBtnPickLocalApk.setText("2. Chọn file APK đã tải để cài đặt");
        }
    }

    // 4. MIRROR ENGINE
    private void renderMirrorEngineDetails() {
        mTvDetailTitle.setText("Mirror Engine");
        mTvDetailCategory.setText("Trình điều khiển Binder & Zygisk Hook");
        mSectionEngineActions.setVisibility(View.VISIBLE);

        boolean active = (MirrorManagerClient.getService() != null);

        setStatusBanner(active,
                active ? "Đang hoạt động (Active)" : "Chưa triển khai (Not deployed)",
                active ? "System Server Hook đã nạp thành công, Binder Service antscreen_mirror đang phản hồi."
                       : "Module Magisk Zygisk chưa được triển khai hoặc System Server chưa được nạp lại.");

        addParamRow("IPC Binder Service", "antscreen_mirror", active, active ? "Online" : "Offline");
        addParamRow("Cơ chế nạp", "Zygisk Hook + libpine native", true, null);
        addParamRow("Thư mục module", MirrorEngineDeployer.MODULE_TARGET_DIR, true, null);
        addParamRow("Quyền điều khiển", "Tương tác chạm 2 chiều, điều hướng ảo, launch app độc lập", true, null);

        if (active) {
            mBtnDeployEngine.setText("Redeploy Mirror Engine");
        } else {
            mBtnDeployEngine.setText("Deploy Mirror Engine");
        }
    }

    private void setStatusBanner(boolean isOk, String headline, String description) {
        if (isOk) {
            mBannerDetailStatus.setBackgroundResource(R.drawable.bg_banner_success);
            mContainerDetailIcon.setBackgroundResource(R.drawable.bg_circle_icon_check);
            mIvDetailStatusIcon.setImageResource(R.drawable.ic_check);
            mTvStatusHeadline.setText(headline);
            mTvStatusHeadline.setTextColor(Color.parseColor("#15803D"));
        } else {
            mBannerDetailStatus.setBackgroundResource(R.drawable.bg_banner_warning);
            mContainerDetailIcon.setBackgroundResource(R.drawable.bg_circle_icon_warning);
            mIvDetailStatusIcon.setImageResource(R.drawable.ic_exclamation);
            mTvStatusHeadline.setText(headline);
            mTvStatusHeadline.setTextColor(Color.parseColor("#B45309"));
        }
        mTvStatusDescription.setText(description);
    }

    private void addParamRow(String label, String value, boolean isOk, @Nullable String badgeText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 10, 0, 10);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        textCol.setLayoutParams(lp);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(12f);
        tvLabel.setTextColor(Color.parseColor("#6B7280"));

        TextView tvValue = new TextView(this);
        tvValue.setText(value != null ? value : "Không xác định");
        tvValue.setTextSize(14f);
        tvValue.setTypeface(null, Typeface.BOLD);
        tvValue.setTextColor(Color.parseColor("#1F2937"));
        tvValue.setPadding(0, 2, 0, 0);

        textCol.addView(tvLabel);
        textCol.addView(tvValue);
        row.addView(textCol);

        if (badgeText != null) {
            TextView tvBadge = new TextView(this);
            tvBadge.setText(badgeText);
            tvBadge.setTextSize(11f);
            tvBadge.setTypeface(null, Typeface.BOLD);
            tvBadge.setPadding(16, 6, 16, 6);
            if (isOk) {
                tvBadge.setBackgroundResource(R.drawable.bg_circle_icon_check);
                tvBadge.setTextColor(Color.parseColor("#15803D"));
            } else {
                tvBadge.setBackgroundResource(R.drawable.bg_circle_icon_warning);
                tvBadge.setTextColor(Color.parseColor("#B45309"));
            }
            row.addView(tvBadge);
        }

        mCardParamsContainer.addView(row);
    }

    // ================= XỬ LÝ CÀI ĐẶT ANDROID AUTO QUA TỆP APK =================

    private void copyAndInstallLocalApk(Uri uri) {
        mContainerAaProgress.setVisibility(View.VISIBLE);
        mPbAaDownload.setProgress(20);
        mTvAaDownloadStatus.setText("Đang trích xuất file APK...");

        new Thread(() -> {
            try {
                File tempApk = new File(getCacheDir(), "picked_gearhead.apk");
                try (InputStream is = getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(tempApk)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                    }
                    fos.flush();
                }

                PackageManager pm = getPackageManager();
                PackageInfo archiveInfo = pm.getPackageArchiveInfo(tempApk.getAbsolutePath(), 0);
                if (archiveInfo == null) {
                    mHandler.post(() -> {
                        mContainerAaProgress.setVisibility(View.GONE);
                        Toast.makeText(this, "Tệp đã chọn không phải tệp APK hợp lệ!", Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                if (!GEARHEAD_PACKAGE.equals(archiveInfo.packageName)) {
                    final String detectedPkg = archiveInfo.packageName;
                    mHandler.post(() -> {
                        mContainerAaProgress.setVisibility(View.GONE);
                        new AlertDialog.Builder(this)
                                .setTitle("Sai tệp ứng dụng")
                                .setMessage("Tệp APK đã chọn là: " + detectedPkg + "\nVui lòng chọn đúng tệp APK của Android Auto (" + GEARHEAD_PACKAGE + ")!")
                                .setPositiveButton("Đóng", null)
                                .show();
                    });
                    return;
                }

                mHandler.post(() -> {
                    GearheadInstaller.installLocalApk(tempApk, new GearheadInstaller.InstallCallback() {
                        @Override
                        public void onProgress(int progress, String statusText) {
                            mPbAaDownload.setProgress(progress);
                            mTvAaDownloadStatus.setText(statusText);
                        }

                        @Override
                        public void onSuccess(String message) {
                            mContainerAaProgress.setVisibility(View.GONE);
                            Toast.makeText(ConditionDetailActivity.this, message, Toast.LENGTH_LONG).show();
                            bindData();
                        }

                        @Override
                        public void onError(String errorMessage) {
                            mContainerAaProgress.setVisibility(View.GONE);
                            new AlertDialog.Builder(ConditionDetailActivity.this)
                                    .setTitle("Cài đặt thất bại")
                                    .setMessage(errorMessage)
                                    .setPositiveButton("Đóng", null)
                                    .show();
                        }
                    });
                });
            } catch (Throwable t) {
                mHandler.post(() -> {
                    mContainerAaProgress.setVisibility(View.GONE);
                    Toast.makeText(this, "Lỗi đọc file: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ================= XỬ LÝ DEPLOY MIRROR ENGINE =================
    private void deployMirrorEngineQuick() {
        mBtnDeployEngine.setEnabled(false);
        mContainerEngineProgress.setVisibility(View.VISIBLE);
        mPbEngineDeploy.setProgress(0);
        mTvEngineDeployStatus.setText("Bắt đầu triển khai...");

        MirrorEngineDeployer.deployQuick(this, new MirrorEngineDeployer.DeployCallback() {
            @Override
            public void onProgress(int progress, String statusText) {
                mPbEngineDeploy.setProgress(progress);
                mTvEngineDeployStatus.setText(statusText);
            }

            @Override
            public void onSuccess(String message) {
                mBtnDeployEngine.setEnabled(true);
                mContainerEngineProgress.setVisibility(View.GONE);
                new AlertDialog.Builder(ConditionDetailActivity.this)
                        .setTitle("Triển khai thành công")
                        .setMessage(message)
                        .setPositiveButton("Khởi động lại ngay", (dialog, which) -> {
                            promptRestartSystemServer();
                        })
                        .setNegativeButton("Để sau", (dialog, which) -> bindData())
                        .show();
            }

            @Override
            public void onError(String errorMessage) {
                mBtnDeployEngine.setEnabled(true);
                mContainerEngineProgress.setVisibility(View.GONE);
                new AlertDialog.Builder(ConditionDetailActivity.this)
                        .setTitle("Triển khai thất bại")
                        .setMessage(errorMessage)
                        .setPositiveButton("Đóng", null)
                        .show();
            }
        });
    }

    private void promptRestartSystemServer() {
        new AlertDialog.Builder(this)
                .setTitle("Khởi động lại thiết bị")
                .setMessage("Hệ thống sẽ khởi động lại toàn bộ điện thoại để nạp đầy đủ các module Magisk và Zygisk.\nBạn có muốn tiếp tục?")
                .setPositiveButton("Khởi động lại", (dialog, which) -> {
                    MirrorEngineDeployer.rebootDevice(new MirrorEngineDeployer.DeployCallback() {
                        @Override
                        public void onProgress(int progress, String statusText) {}

                        @Override
                        public void onSuccess(String message) {
                            Toast.makeText(ConditionDetailActivity.this, message, Toast.LENGTH_LONG).show();
                        }

                        @Override
                        public void onError(String errorMessage) {
                            Toast.makeText(ConditionDetailActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
