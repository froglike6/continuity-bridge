package com.froglike6.continuitybridge;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.app.KeyguardManager;
import android.os.PersistableBundle;
import java.io.IOException;

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
            @Override public boolean setContent(String eventId, ClipboardContent content) { return setClip(eventId, content); }
            @Override public boolean confirmContent(String eventId, ClipboardContent content) { return confirmClip(eventId); }
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
        return setClip(eventId, ClipboardContent.text(text));
    }

    private boolean setClip(String eventId, ClipboardContent content) {
        try {
            final ClipData clip;
            if (content.isImage()) {
                ClipboardImageReader.validated(content.mimeType(), content.bytes());
                android.net.Uri uri = ClipboardImageProvider.store(context, content);
                clip = new ClipData("Continuity Bridge", new String[] {content.mimeType()}, new ClipData.Item(uri));
            } else clip = ClipData.newPlainText("Continuity Bridge", content.text());
            PersistableBundle extras = new PersistableBundle(); extras.putString(EVENT_ID_EXTRA, eventId);
            clip.getDescription().setExtras(extras); clipboard.setPrimaryClip(clip);
            return true;
        } catch (IOException | RuntimeException error) {
            if (content.isImage()) new ConfigStore(context).clipboardCapability(ClipboardImageReader.status(error));
            return false;
        }
    }

    private boolean confirmClip(String eventId) {
        try {
            ClipData current = ShizukuClipboardClient.get(context).read();
            ClipboardObservation observed = ClipboardObservation.fromShizuku(current, "apply:" + eventId);
            if (observed != null && RemoteApplyTracker.confirmContent(observed.marker, observed.content(context), observed.identity)) return true;
        } catch (IOException | SecurityException unavailable) {
            MetadataLog.clipboardMonitor("shizuku_apply_unavailable");
        }
        new ConfigStore(context).clipboardCapability("Shizuku 연결 후 복사를 이어갑니다");
        return false;
    }

    static String marker(ClipDescription description) {
        PersistableBundle extras = description == null ? null : description.getExtras();
        return extras == null ? null : extras.getString(EVENT_ID_EXTRA);
    }
}
