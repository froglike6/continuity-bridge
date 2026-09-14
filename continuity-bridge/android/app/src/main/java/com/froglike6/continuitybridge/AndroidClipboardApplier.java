package com.froglike6.continuitybridge;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.app.KeyguardManager;
import android.os.PersistableBundle;

final class AndroidClipboardApplier implements EventApplier {
    static final String EVENT_ID_EXTRA = "com.froglike6.continuitybridge.EVENT_ID";
    private final Context context;
    private final ClipboardManager clipboard;
    private final ClipboardApplyTransaction transaction;

    AndroidClipboardApplier(Context context, BridgeStateStore store) {
        this.context = context;
        this.clipboard = context.getSystemService(ClipboardManager.class);
        transaction = new ClipboardApplyTransaction(store, new ClipboardSurface() {
            @Override public boolean set(String eventId, String text) { return setClip(eventId, text); }
            @Override public boolean confirm(String eventId, String text) { return confirmClip(eventId); }
        });
    }

    @Override public boolean apply(ProtocolEvent event) {
        if (context.getSystemService(KeyguardManager.class).isDeviceLocked()) {
            new ConfigStore(context).clipboardCapability("잠금 해제 후 복사를 이어갑니다");
            return false;
        }
        boolean success = transaction.apply(event);
        if (success) new ConfigStore(context).clipboardCapability("복사 공유 준비됨");
        if (!success) RemoteApplyTracker.clear(event.eventId()); return success;
    }

    private boolean setClip(String eventId, String text) {
        try {
            ClipData clip = ClipData.newPlainText("Continuity Bridge", text);
            PersistableBundle extras = new PersistableBundle(); extras.putString(EVENT_ID_EXTRA, eventId);
            clip.getDescription().setExtras(extras); clipboard.setPrimaryClip(clip);
            return true;
        } catch (RuntimeException error) { return false; }
    }

    private boolean confirmClip(String eventId) {
        try {
            ClipData observed = clipboard.getPrimaryClip();
            if (observed != null && observed.getItemCount() == 1 && observed.getItemAt(0).getText() != null) {
                long timestamp = observed.getDescription().getTimestamp();
                String identity = timestamp == 0 ? "apply:" + eventId : "timestamp:" + timestamp;
                if (RemoteApplyTracker.confirm(marker(observed.getDescription()), observed.getItemAt(0).getText().toString(), identity)) return true;
            }
        } catch (SecurityException unavailable) { }
        if (Thread.currentThread().isInterrupted()) return false;
        ClipboardCaptureController.requestOverlay(context, "apply:" + eventId);
        boolean confirmed = RemoteApplyTracker.awaitConfirmation(eventId, 2_000);
        if (!confirmed) new ConfigStore(context).clipboardCapability("클립보드 적용 확인 필요");
        return confirmed;
    }

    static String marker(ClipDescription description) {
        PersistableBundle extras = description == null ? null : description.getExtras();
        return extras == null ? null : extras.getString(EVENT_ID_EXTRA);
    }
}
