package com.tho493.antscreen.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.tho493.antscreen.R;
import com.tho493.antscreen.engine.AppManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AppListActivity extends AppCompatActivity {

    private ImageButton mBtnBack;
    private TextView mTvAppCount;
    private EditText mEtSearch;
    private ProgressBar mProgressBar;
    private RecyclerView mRvApps;

    private final List<AppManager.AppItem> mAllApps = new ArrayList<>();
    private final List<AppManager.AppItem> mFilteredApps = new ArrayList<>();
    private AppAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_list);

        mBtnBack = findViewById(R.id.btn_back);
        mTvAppCount = findViewById(R.id.tv_app_count);
        mEtSearch = findViewById(R.id.et_search);
        mProgressBar = findViewById(R.id.progress_bar);
        mRvApps = findViewById(R.id.rv_apps);

        mBtnBack.setOnClickListener(v -> finish());

        mAdapter = new AppAdapter();
        mRvApps.setLayoutManager(new LinearLayoutManager(this));
        mRvApps.setAdapter(mAdapter);

        mEtSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        loadApps();
    }

    private void loadApps() {
        mProgressBar.setVisibility(View.VISIBLE);
        mRvApps.setVisibility(View.GONE);

        new Thread(() -> {
            List<AppManager.AppItem> apps = AppManager.getInstalledApps(this);
            runOnUiThread(() -> {
                mAllApps.clear();
                mAllApps.addAll(apps);
                filterApps(mEtSearch.getText().toString());

                mProgressBar.setVisibility(View.GONE);
                mRvApps.setVisibility(View.VISIBLE);
                updateSelectedCount();
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
        mAdapter.notifyDataSetChanged();
    }

    private int getEnabledCount() {
        int count = 0;
        for (AppManager.AppItem item : mAllApps) {
            if (item.isEnabled) count++;
        }
        return count;
    }

    private void updateSelectedCount() {
        int selected = getEnabledCount();
        mTvAppCount.setText("Đã bật " + selected + " / " + AppManager.MAX_SLOTS + " ứng dụng cho xe");
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

            // Temporarily remove listener to avoid triggering during recycle
            holder.switchEnable.setOnCheckedChangeListener(null);
            holder.switchEnable.setChecked(item.isEnabled);
            holder.switchEnable.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked && getEnabledCount() >= AppManager.MAX_SLOTS) {
                    btn.setChecked(false);
                    android.widget.Toast.makeText(AppListActivity.this,
                            "Đã đạt giới hạn tối đa " + AppManager.MAX_SLOTS + " ứng dụng!",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                item.isEnabled = isChecked;
                AppManager.setPackageInjected(AppListActivity.this, item.packageName, isChecked);
                updateSelectedCount();
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
}
