package com.froglike6.continuitybridge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import java.io.IOException;

final class ClipboardObservation {
    final String marker;
    final String identity;
    final long capturedAtMs;
    final RemoteApplyTracker.Observation remote;
    private final String text;
    private final Uri uri;
    private final String mimeType;
    private final ClipData privilegedClip;

    private ClipboardObservation(ClipData clip, String fallbackIdentity) {
        this(clip, fallbackIdentity, false);
    }

    private ClipboardObservation(ClipData clip, String fallbackIdentity, boolean privileged) {
        marker = AndroidClipboardApplier.marker(clip.getDescription());
        long timestamp = clip.getDescription().getTimestamp();
        identity = timestamp == 0 ? fallbackIdentity : "timestamp:" + timestamp;
        capturedAtMs = System.currentTimeMillis();
        remote = RemoteApplyTracker.observe(marker, identity);
        ClipData.Item item = clip.getItemAt(0);
        boolean image = item.getUri() != null && clip.getDescription().hasMimeType("image/*");
        uri = image ? item.getUri() : null;
        text = image || item.getText() == null ? null : item.getText().toString();
        mimeType = clip.getDescription().hasMimeType("image/png") ? "image/png"
                : clip.getDescription().hasMimeType("image/jpeg") ? "image/jpeg" : null;
        privilegedClip = privileged && image ? clip : null;
        if (text != null) RemoteApplyTracker.confirm(marker, text, identity);
    }

    static ClipboardObservation read(ClipboardManager clipboard, String fallbackIdentity) {
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() != 1) return null;
        return new ClipboardObservation(clip, fallbackIdentity);
    }

    ClipboardContent content(Context context) throws IOException {
        if (privilegedClip != null) return ShizukuClipboardClient.get(context).readImage(privilegedClip);
        if (uri != null) return ClipboardImageReader.read(context.getContentResolver(), uri, mimeType);
        if (text == null) throw new IOException("image_format");
        try { return ClipboardContent.text(text); }
        catch (IllegalArgumentException invalid) { throw new IOException("clipboard_text_size", invalid); }
    }

    static ClipboardObservation fromShizuku(ClipData clip, String fallbackIdentity) {
        if (clip == null || clip.getItemCount() != 1) return null;
        return new ClipboardObservation(clip, fallbackIdentity, true);
    }

    boolean isImage() { return uri != null; }
}
