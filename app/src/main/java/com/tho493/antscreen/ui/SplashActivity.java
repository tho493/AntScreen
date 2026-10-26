package com.tho493.antscreen.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.tho493.antscreen.R;

public class SplashActivity extends AppCompatActivity {

    private ImageView mIvLogo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        mIvLogo = findViewById(R.id.iv_splash_logo);

        // Đảm bảo View đã đo đạc kích thước đầy đủ trước khi gán pivot
        mIvLogo.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mIvLogo.getViewTreeObserver().removeOnPreDrawListener(this);

                // Tâm của chiếc điện thoại trong logo (chiếc điện thoại nằm ở 44.8% width, 56.8% height)
                float phonePivotX = mIvLogo.getWidth() * 0.448f;
                float phonePivotY = mIvLogo.getHeight() * 0.568f;

                mIvLogo.setPivotX(phonePivotX);
                mIvLogo.setPivotY(phonePivotY);

                // Giữ logo hiển thị nhanh 180ms, sau đó zoom phóng đại dứt khoát
                mIvLogo.postDelayed(SplashActivity.this::startPhoneZoomAnimation, 180);

                return true;
            }
        });
    }

    private void startPhoneZoomAnimation() {
        if (isFinishing() || isDestroyed()) return;

        // Phóng to siêu tốc chiếc điện thoại từ tâm tràn màn hình (380ms)
        mIvLogo.animate()
                .scaleX(14.0f)
                .scaleY(14.0f)
                .setDuration(380)
                .setInterpolator(new AccelerateInterpolator(1.8f))
                .withEndAction(this::navigateToMain)
                .start();
    }

    private void navigateToMain() {
        if (isFinishing()) return;

        Intent intent = new Intent(SplashActivity.this, MainActivity.class);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
