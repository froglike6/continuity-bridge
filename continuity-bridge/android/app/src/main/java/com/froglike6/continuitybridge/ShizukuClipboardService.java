package com.froglike6.continuitybridge;

import android.content.ClipData;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import java.io.IOException;

public final class ShizukuClipboardService extends IShizukuClipboard.Stub {
    private static final String OWNER = "com.froglike6.continuitybridge";
    private final int ownerUid;
    private final HandlerThread worker = new HandlerThread("clipboard-shizuku");
    private final Handler handler;
    private final ShizukuClipboardAccess clipboard;
    private final ShizukuClipboardImages images;
    private IShizukuClipboardListener listener;
    private boolean listenerLinked;
    private final IBinder.DeathRecipient listenerDeath = new IBinder.DeathRecipient() {
        @Override public void binderDied() {
            handler.post(new Runnable() {
                @Override public void run() { detach(); System.exit(0); }
            });
        }
    };
    private final Runnable changed = new Runnable() {
        @Override public void run() {
            handler.post(new Runnable() { @Override public void run() { publish(); } });
        }
    };

    public ShizukuClipboardService(Context context) throws Exception {
        if (Process.myUid() != 2000) throw new SecurityException("shizuku_shell_required");
        ApplicationInfo owner = context.getPackageManager().getApplicationInfo(OWNER, 0);
        if (!OWNER.equals(owner.packageName) || owner.uid < 10_000) throw new SecurityException("clipboard_owner_invalid");
        ownerUid = owner.uid;
        clipboard = new ShizukuClipboardAccess(ownerUid / 100_000);
        images = new ShizukuClipboardImages(clipboard, ownerUid / 100_000);
        worker.start();
        handler = new Handler(worker.getLooper());
    }

    @Override public void registerListener(final IShizukuClipboardListener callback) {
        requireOwner();
        if (callback == null) throw new IllegalArgumentException("clipboard_listener_missing");
        handler.post(new Runnable() {
            @Override public void run() {
                detach();
                listener = callback;
                try {
                    callback.asBinder().linkToDeath(listenerDeath, 0);
                    listenerLinked = true;
                    clipboard.register(changed);
                    callback.onReady();
                    publish();
                } catch (ReflectiveOperationException | RemoteException | RuntimeException error) {
                    fail(ShizukuClipboardAccess.errorCode(error));
                    detach();
                }
            }
        });
    }

    @Override public void unregisterListener(final IShizukuClipboardListener callback) {
        requireOwner();
        handler.post(new Runnable() {
            @Override public void run() {
                if (listener != null && callback != null && listener.asBinder().equals(callback.asBinder())) detach();
            }
        });
    }

    @Override public ClipData read() {
        requireOwner();
        long identity = Binder.clearCallingIdentity();
        try { return clipboard.read(); }
        catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(ShizukuClipboardAccess.errorCode(error));
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public ParcelFileDescriptor openImage(String expectedUri, long expectedTimestamp) {
        requireOwner();
        if (expectedUri == null || expectedUri.length() > 16_384) throw new IllegalArgumentException("image_uri_invalid");
        try { return images.open(expectedUri, expectedTimestamp); }
        catch (IOException error) { throw new IllegalStateException("image_unavailable"); }
    }

    @Override public void destroy() {
        int caller = Binder.getCallingUid();
        if (caller != ownerUid && caller != Process.myUid()) throw new SecurityException("clipboard_caller_rejected");
        handler.post(new Runnable() {
            @Override public void run() { detach(); System.exit(0); }
        });
    }

    private void requireOwner() {
        if (Binder.getCallingUid() != ownerUid) throw new SecurityException("clipboard_caller_rejected");
    }

    private void publish() {
        if (listener == null) return;
        try {
            ClipData clip = clipboard.read();
            if (clip != null) listener.onClip(clip);
        } catch (ReflectiveOperationException | RemoteException | RuntimeException error) {
            fail(ShizukuClipboardAccess.errorCode(error));
        }
    }

    private void fail(String code) {
        if (listener == null) return;
        try { listener.onError(code); }
        catch (RemoteException gone) { detach(); }
    }

    private void detach() {
        if (listener != null && listenerLinked) listener.asBinder().unlinkToDeath(listenerDeath, 0);
        listenerLinked = false;
        listener = null;
        try { clipboard.unregister(); }
        catch (ReflectiveOperationException | RuntimeException error) {
            android.util.Log.w("ShizukuClipboard", "listener_cleanup_failed");
        }
    }
}
