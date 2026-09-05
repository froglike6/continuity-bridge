package com.froglike6.clipboardprobe;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ClipCascadeProbeActivity extends Activity {
    private final BroadcastReceiver captureReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshState();
        }
    };

    private TextView statusView;
    private TextView captureView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("ClipCascade 방식 테스트");
        title.setTextSize(24);

        TextView explanation = new TextView(this);
        explanation.setText("백그라운드 복사를 감지하면 순간 오버레이로 클립보드를 읽고 알림에 표시합니다.");
        explanation.setTextSize(16);
        explanation.setPadding(0, padding / 2, 0, padding);

        statusView = new TextView(this);
        statusView.setTextSize(18);
        captureView = new TextView(this);
        captureView.setTextSize(18);
        captureView.setPadding(0, padding / 2, 0, padding);

        Button startButton = new Button(this);
        startButton.setText("모니터링 시작");
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startMonitoring();
            }
        });

        Button stopButton = new Button(this);
        stopButton.setText("모니터링 중지");
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopMonitoring();
            }
        });

        layout.addView(title);
        layout.addView(explanation);
        layout.addView(statusView);
        layout.addView(captureView);
        layout.addView(startButton);
        layout.addView(stopButton);
        setContentView(layout);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ClipboardMonitorService.ACTION_CAPTURED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(captureReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(captureReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(captureReceiver);
        super.onStop();
    }

    private void startMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
            statusView.setText("상태: 알림 권한을 허용한 뒤 다시 시작하세요.");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settingsIntent);
            statusView.setText("상태: 다른 앱 위에 표시 권한을 허용한 뒤 다시 시작하세요.");
            return;
        }
        Intent serviceIntent = new Intent(this, ClipboardMonitorService.class)
                .setAction(ClipboardMonitorService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        statusView.setText("상태: 모니터링 시작 중");
    }

    private void stopMonitoring() {
        Intent serviceIntent = new Intent(this, ClipboardMonitorService.class)
                .setAction(ClipboardMonitorService.ACTION_STOP);
        startService(serviceIntent);
        statusView.setText("상태: 중지됨");
    }

    private void refreshState() {
        SharedPreferences preferences = getSharedPreferences(
                ClipboardMonitorService.PREFERENCES, MODE_PRIVATE);
        boolean running = preferences.getBoolean(ClipboardMonitorService.KEY_RUNNING, false);
        String captured = preferences.getString(ClipboardMonitorService.KEY_CAPTURED, null);
        statusView.setText(running ? "상태: 모니터링 중" : "상태: 중지됨");
        captureView.setText(captured == null ? "마지막 캡처: 없음" : "마지막 캡처: " + captured);
    }
}
