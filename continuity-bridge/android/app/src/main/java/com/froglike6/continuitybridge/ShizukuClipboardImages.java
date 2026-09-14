package com.froglike6.continuitybridge;

import android.content.ClipData;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

final class ShizukuClipboardImages {
    private final ShizukuClipboardAccess clipboard;
    private final int userId;
    private final ThreadPoolExecutor transfers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(1));

    ShizukuClipboardImages(ShizukuClipboardAccess clipboard, int userId) {
        this.clipboard = clipboard;
        this.userId = userId;
    }

    ParcelFileDescriptor open(final String expectedUri, final long expectedTimestamp) throws IOException {
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        try {
            transfers.execute(new Runnable() {
                @Override public void run() {
                    try {
                        ClipData clip = clipboard.read();
                        Uri uri = currentImage(clip, expectedUri, expectedTimestamp);
                        copy(uri, pipe[1]);
                    } catch (ReflectiveOperationException | IOException | RuntimeException error) {
                        try { pipe[1].closeWithError("clipboard_image_unavailable"); }
                        catch (IOException closeError) { android.util.Log.w("ShizukuClipboard", "image_pipe_close_failed"); }
                    }
                }
            });
        } catch (RejectedExecutionException full) {
            pipe[0].close();
            pipe[1].close();
            throw new IOException("image_busy", full);
        }
        return pipe[0];
    }

    private static Uri currentImage(ClipData clip, String expectedUri, long timestamp) throws IOException {
        if (clip == null || clip.getItemCount() != 1 || clip.getDescription().getTimestamp() != timestamp)
            throw new IOException("image_clip_changed");
        Uri uri = clip.getItemAt(0).getUri();
        if (uri == null || !"content".equals(uri.getScheme()) || !uri.toString().equals(expectedUri))
            throw new IOException("image_clip_changed");
        if (!clip.getDescription().hasMimeType("image/*"))
            throw new IOException("image_format");
        if (uri.getAuthority() == null || uri.getAuthority().contains("@")) throw new IOException("image_user");
        return uri;
    }

    private void copy(Uri uri, ParcelFileDescriptor output) throws ReflectiveOperationException, IOException {
        Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Class<?> managerType = Class.forName("android.app.IActivityManager");
        IBinder token = new Binder();
        Object holder = managerType.getMethod("getContentProviderExternal", String.class, int.class,
                IBinder.class, String.class).invoke(manager, uri.getAuthority(), userId, token, "continuity_clipboard");
        if (holder == null) throw new IOException("image_provider_missing");
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            try (AssetFileDescriptor asset = openAsset(provider, uri)) {
                if (asset == null) throw new IOException("image_unavailable");
                if (asset.getLength() > ClipboardContent.MAX_IMAGE_BYTES) throw new IOException("image_size");
                OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(output);
                try (InputStream input = asset.createInputStream()) {
                    byte[] buffer = new byte[8_192];
                    int total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (count > ClipboardContent.MAX_IMAGE_BYTES - total) throw new IOException("image_size");
                        stream.write(buffer, 0, count);
                        total += count;
                    }
                }
                stream.close();
            }
        } finally {
            managerType.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                    .invoke(manager, uri.getAuthority(), token, userId);
        }
    }

    private static AssetFileDescriptor openAsset(Object provider, Uri uri) throws ReflectiveOperationException {
        Class<?> contract = Class.forName("android.content.IContentProvider");
        Class<?> cancellation = Class.forName("android.os.ICancellationSignal");
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            Class<?> source = Class.forName("android.content.AttributionSource");
            Class<?> builder = Class.forName("android.content.AttributionSource$Builder");
            Object value = builder.getConstructor(int.class).newInstance(Process.myUid());
            builder.getMethod("setPackageName", String.class).invoke(value, "com.android.shell");
            Object attribution = builder.getMethod("build").invoke(value);
            Method open = contract.getMethod("openAssetFile", source, Uri.class, String.class, cancellation);
            return (AssetFileDescriptor) open.invoke(provider, attribution, uri, "r", null);
        }
        if (android.os.Build.VERSION.SDK_INT == 30) {
            Method open = contract.getMethod("openAssetFile", String.class, String.class, Uri.class, String.class, cancellation);
            return (AssetFileDescriptor) open.invoke(provider, "com.android.shell", null, uri, "r", null);
        }
        Method open = contract.getMethod("openAssetFile", String.class, Uri.class, String.class, cancellation);
        return (AssetFileDescriptor) open.invoke(provider, "com.android.shell", uri, "r", null);
    }
}
