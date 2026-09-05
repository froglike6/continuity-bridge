package com.froglike6.continuitybridge;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

public final class ClipboardOverlayActivity extends Activity {
    private WindowManager manager;
    private View overlay;
    private boolean attached;
    private boolean captured;
    private String observationIdentity;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); observationIdentity = getIntent().getStringExtra(ClipboardCaptureController.EXTRA_OBSERVATION_ID);
        if (observationIdentity == null) { finish(); return; }
        manager = getSystemService(WindowManager.class); overlay = new View(this); overlay.setBackgroundColor(Color.TRANSPARENT);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        try { manager.addView(overlay, params); attached = true; overlay.setFocusableInTouchMode(true); overlay.requestFocus();
            handler.postDelayed(new Runnable() { @Override public void run() { captureAndClose(); } }, 1_000);
        } catch (SecurityException error) { new ConfigStore(this).clipboardCapability("다른 앱 위 표시 권한 필요"); finish(); }
    }

    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) captureAndClose(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); removeOverlay(); ClipboardCaptureController.OVERLAY_GATE.complete(observationIdentity); super.onDestroy(); }

    private void captureAndClose() {
        if (captured) return; captured = true;
        CaptureResult result = ClipboardCaptureController.captureOverlay(this, observationIdentity);
        if (result == CaptureResult.UNAVAILABLE) new ConfigStore(this).clipboardCapability("클립보드 읽기 실패");
        removeOverlay(); finish();
    }
    private void removeOverlay() {
        if (!attached) return; manager.removeViewImmediate(overlay); attached = false;
    }
}
