package com.froglike6.clipboardprobe;

import android.app.Activity;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

public final class ClipboardOverlayActivity extends Activity {
    private static final String LOG_TAG = "ClipCascadeProbe";

    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener =
            new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (floatingView != null && floatingView.getViewTreeObserver().isAlive()) {
                        floatingView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                    captureAndClose();
                }
            };

    private WindowManager windowManager;
    private View floatingView;
    private boolean attached;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createFloatingView();
        floatingView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        makeFloatingViewFocusable();
    }

    @Override
    protected void onDestroy() {
        removeFloatingView();
        super.onDestroy();
    }

    private void createFloatingView() {
        floatingView = new View(this);
        floatingView.setBackgroundColor(Color.TRANSPARENT);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(floatingView, params);
        attached = true;
    }

    private void makeFloatingViewFocusable() {
        WindowManager.LayoutParams params =
                (WindowManager.LayoutParams) floatingView.getLayoutParams();
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        windowManager.updateViewLayout(floatingView, params);
    }

    private void captureAndClose() {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            String text = ClipboardMonitorService.readText(manager);
            if (text != null) {
                ClipboardMonitorService.publishCapture(this, text);
            } else {
                Log.w(LOG_TAG, "clipboard.overlay_read_empty");
            }
        } catch (SecurityException exception) {
            Log.e(LOG_TAG, "clipboard.overlay_read_denied", exception);
        } finally {
            removeFloatingView();
            finish();
        }
    }

    private void removeFloatingView() {
        if (!attached) {
            return;
        }
        if (floatingView.getViewTreeObserver().isAlive()) {
            floatingView.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        }
        windowManager.removeViewImmediate(floatingView);
        attached = false;
    }
}
