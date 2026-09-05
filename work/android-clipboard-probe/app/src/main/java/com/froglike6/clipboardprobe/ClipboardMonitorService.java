package com.froglike6.clipboardprobe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class ClipboardMonitorService extends Service {
    static final String ACTION_START = "com.froglike6.clipboardprobe.action.START";
    static final String ACTION_STOP = "com.froglike6.clipboardprobe.action.STOP";
    static final String ACTION_CAPTURED = "com.froglike6.clipboardprobe.action.CAPTURED";
    static final String PREFERENCES = "clipcascade_probe";
    static final String KEY_RUNNING = "running";
    static final String KEY_CAPTURED = "captured";

    private static final String LOG_TAG = "ClipCascadeProbe";
    private static final String MONITOR_CHANNEL = "clipcascade_monitor";
    private static final String CAPTURE_CHANNEL = "clipcascade_capture";
    private static final int MONITOR_NOTIFICATION_ID = 4100;
    private static final int CAPTURE_NOTIFICATION_ID = 4200;
    private static final long OVERLAY_DEBOUNCE_MS = 750L;

    private final ClipboardManager.OnPrimaryClipChangedListener clipboardListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    String text = readText((ClipboardManager) getSystemService(CLIPBOARD_SERVICE));
                    if (text != null) {
                        publishCapture(ClipboardMonitorService.this, text);
                    }
                }
            };

    private ClipboardManager clipboardManager;
    private Process logcatProcess;
    private Thread logcatThread;
    private boolean monitoring;
    private volatile boolean stopping;
    private long lastOverlayRequestMs;

    @Override
    public void onCreate() {
        super.onCreate();
        clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startMonitoring();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        if (monitoring) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener);
            monitoring = false;
        }
        if (logcatProcess != null) {
            logcatProcess.destroy();
        }
        if (logcatThread != null) {
            logcatThread.interrupt();
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        Log.i(LOG_TAG, "clipboard.monitor_stopped");
        super.onDestroy();
    }

    private void startMonitoring() {
        if (monitoring) {
            return;
        }
        startForeground(MONITOR_NOTIFICATION_ID, new Notification.Builder(this, MONITOR_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ClipCascade 테스트")
                .setContentText("클립보드 복사를 감지하는 중")
                .setOngoing(true)
                .build());
        clipboardManager.addPrimaryClipChangedListener(clipboardListener);
        monitoring = true;
        stopping = false;
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply();
        startLogcatReader();
        Log.i(LOG_TAG, "clipboard.monitor_started");
    }

    private void startLogcatReader() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                || checkSelfPermission(Manifest.permission.READ_LOGS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(LOG_TAG, "clipboard.log_access_unavailable");
            return;
        }
        logcatThread = new Thread(new Runnable() {
            @Override
            public void run() {
                readClipboardErrors();
            }
        }, "clipboard-log-reader");
        logcatThread.setDaemon(true);
        logcatThread.start();
    }

    private void readClipboardErrors() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
        try {
            logcatProcess = new ProcessBuilder(
                    "logcat", "-T", timestamp, "ClipboardService:E", "*:S")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(logcatProcess.getInputStream()))) {
                String line;
                while (!stopping && (line = reader.readLine()) != null) {
                    if (ClipboardLogMatcher.requestsOverlay(line, getPackageName())) {
                        requestOverlay();
                    }
                }
            }
        } catch (IOException exception) {
            if (!stopping) {
                Log.e(LOG_TAG, "clipboard.log_reader_failed", exception);
            }
        }
    }

    private void requestOverlay() {
        long now = System.currentTimeMillis();
        if (now - lastOverlayRequestMs < OVERLAY_DEBOUNCE_MS) {
            return;
        }
        lastOverlayRequestMs = now;
        Intent overlayIntent = new Intent(this, ClipboardOverlayActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(overlayIntent);
        Log.i(LOG_TAG, "clipboard.overlay_requested");
    }

    static void publishCapture(Context context, String text) {
        context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(KEY_CAPTURED, text)
                .apply();
        NotificationManager manager =
                (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        String displayed = text.length() <= 160 ? text : text.substring(0, 160);
        Notification notification = new Notification.Builder(context, CAPTURE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("클립보드 캡처 성공")
                .setContentText(displayed)
                .setStyle(new Notification.BigTextStyle().bigText(displayed))
                .setAutoCancel(true)
                .build();
        manager.notify(CAPTURE_NOTIFICATION_ID, notification);
        context.sendBroadcast(new Intent(ACTION_CAPTURED).setPackage(context.getPackageName()));
        Log.i(LOG_TAG, "clipboard.capture_succeeded");
    }

    static String readText(ClipboardManager manager) {
        ClipData clip = manager.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        CharSequence text = clip.getItemAt(0).getText();
        return text == null ? null : text.toString();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(new NotificationChannel(
                    MONITOR_CHANNEL, "ClipCascade 모니터", NotificationManager.IMPORTANCE_LOW));
            manager.createNotificationChannel(new NotificationChannel(
                    CAPTURE_CHANNEL, "클립보드 캡처", NotificationManager.IMPORTANCE_HIGH));
        }
    }
}
