package com.froglike6.continuitybridge;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class ClipboardCaptureController {
    private static final String TAG = "ContinuityBridge";
    static final String EXTRA_OBSERVATION_ID = "com.froglike6.continuitybridge.OBSERVATION_ID";
    static final OverlayRequestGate OVERLAY_GATE = new OverlayRequestGate();
    private final Context context;
    private final ClipboardManager clipboard;
    private final DurableOutbox outbox;
    private final ClipboardManager.OnPrimaryClipChangedListener listener;
    private volatile boolean stopping;
    private Process logcat;
    private Thread reader;
    private final String observationEpoch = UUID.randomUUID().toString();
    private final AtomicLong observationSequence = new AtomicLong();
    private volatile String lastObservationIdentity;

    ClipboardCaptureController(Context context, DurableOutbox outbox) {
        this.context = context; this.outbox = outbox;
        clipboard = context.getSystemService(ClipboardManager.class);
        listener = new ClipboardManager.OnPrimaryClipChangedListener() {
            @Override public void onPrimaryClipChanged() {
                if (stopping) return;
                String identity = "callback:" + observationEpoch + ":" + observationSequence.incrementAndGet();
                lastObservationIdentity = identity;
                capture(identity);
            }
        };
    }

    void start() {
        clipboard.addPrimaryClipChangedListener(listener); stopping = false;
        if (Build.VERSION.SDK_INT > 28 && context.checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            new ConfigStore(context).clipboardCapability("READ_LOGS 권한 필요"); return;
        }
        reader = new Thread(new Runnable() { @Override public void run() { readDenials(); } }, "continuity-clipboard-logcat");
        reader.start(); new ConfigStore(context).clipboardCapability("감지 준비됨");
    }

    void stop() {
        synchronized (this) {
            stopping = true;
            if (logcat != null) logcat.destroy();
        }
        clipboard.removePrimaryClipChangedListener(listener);
        if (reader != null) reader.interrupt();
    }

    CaptureResult capture(String fallbackIdentity) {
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() != 1) return CaptureResult.INVALID;
            CharSequence value = clip.getItemAt(0).getText(); if (value == null) return CaptureResult.INVALID;
            long timestamp = clip.getDescription().getTimestamp();
            String identity = timestamp == 0 ? fallbackIdentity : "timestamp:" + timestamp;
            String marker = AndroidClipboardApplier.marker(clip.getDescription());
            return outbox.captureClipboard(value.toString(), marker, identity, System.currentTimeMillis());
        } catch (SecurityException error) {
            outbox.observeUnavailableCallback(fallbackIdentity);
            return CaptureResult.UNAVAILABLE;
        }
    }

    static CaptureResult captureOverlay(Context context, String observationIdentity) {
        try { return BridgeRepository.get(context).outbox() == null ? CaptureResult.UNAVAILABLE
                : new ClipboardCaptureController(context, BridgeRepository.get(context).outbox()).capture(observationIdentity); }
        catch (IOException error) { return CaptureResult.UNAVAILABLE; }
    }

    private void readDenials() {
        Process process = null;
        try {
            process = new ProcessBuilder("logcat", "-v", "brief", "-T", "1", "ClipboardService:E", "*:S").redirectErrorStream(true).start();
            synchronized (this) {
                logcat = process;
                if (stopping) return;
            }
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line; while (!stopping && (line = lines.readLine()) != null) if (ClipboardDenialMatcher.matches(line, context.getPackageName())) requestOverlay();
            }
        } catch (IOException error) { if (!stopping) new ConfigStore(context).clipboardCapability("로그 감지 실패"); }
        finally {
            if (process != null) process.destroy();
            synchronized (this) { if (logcat == process) logcat = null; }
        }
    }

    private void requestOverlay() {
        if (stopping) return;
        String candidate = lastObservationIdentity;
        if (candidate == null) candidate = "denial:" + observationEpoch + ":" + observationSequence.incrementAndGet();
        requestOverlay(context, candidate);
    }

    static void requestOverlay(Context context, String candidate) {
        if (!Settings.canDrawOverlays(context)) { new ConfigStore(context).clipboardCapability("다른 앱 위 표시 권한 필요"); return; }
        String identity = OVERLAY_GATE.begin(candidate); if (identity == null) return;
        Intent intent = new Intent(context, ClipboardOverlayActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra(EXTRA_OBSERVATION_ID, identity);
        try { context.startActivity(intent); } catch (RuntimeException error) { OVERLAY_GATE.complete(identity); new ConfigStore(context).clipboardCapability("오버레이 시작 실패"); }
    }
}
