package com.froglike6.continuitybridge;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.os.PersistableBundle;

final class AndroidClipboardApplier implements EventApplier {
    static final String EVENT_ID_EXTRA = "com.froglike6.continuitybridge.EVENT_ID";
    private final ClipboardManager clipboard;
    private final ClipboardApplyTransaction transaction;

    AndroidClipboardApplier(ClipboardManager clipboard, BridgeStateStore store) {
        this.clipboard = clipboard;
        transaction = new ClipboardApplyTransaction(store, new ClipboardSurface() {
            @Override public boolean set(String eventId, String text) { return setClip(eventId, text); }
        });
    }

    @Override public boolean apply(ProtocolEvent event) {
        boolean success = transaction.apply(event);
        if (!success) RemoteApplyTracker.clear(event.eventId()); return success;
    }

    private boolean setClip(String eventId, String text) {
        try {
            ClipData clip = ClipData.newPlainText("Continuity Bridge", text);
            PersistableBundle extras = new PersistableBundle(); extras.putString(EVENT_ID_EXTRA, eventId);
            clip.getDescription().setExtras(extras); clipboard.setPrimaryClip(clip);
            ClipDescription observed = clipboard.getPrimaryClipDescription();
            if (observed != null && observed.getTimestamp() != 0) {
                RemoteApplyTracker.bindObservation(eventId, "timestamp:" + observed.getTimestamp());
            }
            return true;
        } catch (RuntimeException error) { return false; }
    }

    static String marker(ClipDescription description) {
        PersistableBundle extras = description == null ? null : description.getExtras();
        return extras == null ? null : extras.getString(EVENT_ID_EXTRA);
    }
}
