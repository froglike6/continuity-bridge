package com.froglike6.continuitybridge;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class ShizukuClipboardClient {
    enum State { MISSING, NOT_RUNNING, PERMISSION_REQUIRED, CONNECTING, READY, ERROR, STOPPED }
    interface StateListener { void onState(State state); }
    interface Listener extends StateListener { void onClip(ClipData clip); }
    private static ShizukuClipboardClient instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<StateListener> observers = new ArrayList<>();
    private final EmbeddedHelperManager helper;
    private volatile State state = State.STOPPED;
    private volatile IShizukuClipboard remote;
    private Listener listener;
    private Connection connection;
    private int failures;
    private final Runnable retry = new Runnable() { @Override public void run() { refreshOnMain(); } };
    private final Runnable timeout = new Runnable() {
        @Override public void run() { if (state == State.CONNECTING) failed(); }
    };

    static synchronized ShizukuClipboardClient get(Context context) {
        if (instance == null) instance = new ShizukuClipboardClient(context.getApplicationContext());
        return instance;
    }

    private ShizukuClipboardClient(Context context) {
        this.context = context;
        helper = EmbeddedHelperManager.get(context);
        helper.addObserver(new EmbeddedHelperManager.Observer() {
            @Override public void onState(EmbeddedHelperManager.State state) { refreshOnMain(); }
        });
    }

    State getState() { return state; }
    void start(final Listener value) {
        main.post(new Runnable() {
            @Override public void run() { listener = value; failures = 0; helper.start(); refreshOnMain(); }
        });
    }
    void stop(final Listener expected) {
        main.post(new Runnable() {
            @Override public void run() {
                if (listener != expected) return;
                listener = null; disconnect(); refreshOnMain();
            }
        });
    }
    void refresh() { main.post(retry); }
    void addStateListener(final StateListener observer) {
        main.post(new Runnable() {
            @Override public void run() { if (!observers.contains(observer)) observers.add(observer); observer.onState(state); }
        });
    }
    void removeStateListener(final StateListener observer) {
        main.post(new Runnable() { @Override public void run() { observers.remove(observer); } });
    }

    ClipData read() throws IOException {
        requireBackground();
        IShizukuClipboard service = remote;
        if (service == null) throw new IOException("shizuku_unavailable");
        try { return service.read(); }
        catch (RemoteException | RuntimeException error) {
            main.post(new Runnable() { @Override public void run() { if (remote == service) failed(); } });
            throw new IOException("shizuku_read_failed", error);
        }
    }

    ClipboardContent readImage(ClipData expected) throws IOException {
        requireBackground();
        if (expected == null || expected.getItemCount() != 1 || expected.getItemAt(0).getUri() == null)
            throw new IOException("image_format");
        IShizukuClipboard service = remote;
        if (service == null) throw new IOException("shizuku_unavailable");
        try {
            final ParcelFileDescriptor descriptor = service.openImage(expected.getItemAt(0).getUri().toString(),
                    expected.getDescription().getTimestamp());
            if (descriptor == null) throw new IOException("image_unavailable");
            final AtomicBoolean expired = new AtomicBoolean();
            final Runnable deadline = new Runnable() {
                @Override public void run() {
                    expired.set(true);
                    if (remote == service) failed();
                    try { descriptor.close(); }
                    catch (IOException error) { android.util.Log.w("ShizukuClipboard", "image_timeout_close_failed"); }
                }
            };
            main.postDelayed(deadline, 10_000);
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8_192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > ClipboardContent.MAX_IMAGE_BYTES - bytes.size()) throw new IOException("image_size");
                    bytes.write(buffer, 0, count);
                }
                if (expired.get()) throw new IOException("image_timeout");
                byte[] image = bytes.toByteArray();
                String mime = image.length >= 4 && image[0] == (byte) 137 && image[1] == 80
                        && image[2] == 78 && image[3] == 71 ? "image/png" : "image/jpeg";
                return ClipboardImageReader.validated(mime, image);
            } finally { main.removeCallbacks(deadline); }
        } catch (RemoteException | RuntimeException error) { throw new IOException("image_unavailable", error); }
    }

    private static void requireBackground() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("clipboard_read_requires_worker");
    }

    private void refreshOnMain() {
        main.removeCallbacks(retry);
        final State available;
        switch (helper.state()) {
            case READY: available = State.STOPPED; break;
            case UNPAIRED: case PAIRING_REQUIRED: available = State.PERMISSION_REQUIRED; break;
            case PAIRING: case STARTING: available = State.CONNECTING; break;
            case UNSUPPORTED: available = State.MISSING; break;
            case ERROR: available = State.ERROR; break;
            default: available = State.NOT_RUNNING; break;
        }
        if (available != State.STOPPED) { disconnect(); setState(available); return; }
        if (listener == null) { setState(State.STOPPED); return; }
        if (connection != null) { listener.onState(state); return; }
        connection = new Connection();
        setState(State.CONNECTING);
        main.postDelayed(timeout, 10_000);
        IBinder value = helper.binder();
        if (value == null) { failed(); return; }
        connection.onServiceConnected(new ComponentName(context, ShizukuClipboardService.class), value);
    }

    private void disconnect() {
        main.removeCallbacks(retry);
        main.removeCallbacks(timeout);
        Connection previous = connection;
        connection = null;
        final IShizukuClipboard previousRemote = remote;
        remote = null;
        if (previous == null) return;
        if (previous.linked) previous.binder.unlinkToDeath(previous, 0);
        if (previousRemote != null) io.execute(new Runnable() { @Override public void run() {
            try { previousRemote.unregisterListener(previous.callback); }
            catch (RemoteException | RuntimeException error) { android.util.Log.i("ShizukuClipboard", "listener_already_stopped"); }
        }});
    }

    private void failed() {
        disconnect();
        setState(State.ERROR);
        if (listener != null) helper.restart();
        if (listener != null) main.postDelayed(retry, Math.min(30_000, 1_000L << Math.min(failures++, 5)));
    }

    private void setState(State value) {
        state = value;
        if (listener != null) listener.onState(value);
        for (StateListener observer : new ArrayList<StateListener>(observers)) observer.onState(value);
    }

    private final class Connection implements ServiceConnection, IBinder.DeathRecipient {
        private IBinder binder;
        private boolean linked;
        private final IShizukuClipboardListener callback = new IShizukuClipboardListener.Stub() {
            @Override public void onReady() {
                main.post(new Runnable() {
                    @Override public void run() {
                        if (connection != Connection.this) return;
                        failures = 0;
                        main.removeCallbacks(timeout);
                        setState(State.READY);
                    }
                });
            }
            @Override public void onClip(final ClipData clip) {
                main.post(new Runnable() {
                    @Override public void run() { if (connection == Connection.this && listener != null) listener.onClip(clip); }
                });
            }
            @Override public void onError(String code) {
                main.post(new Runnable() { @Override public void run() { if (connection == Connection.this) failed(); } });
            }
        };
        @Override public void onServiceConnected(ComponentName name, IBinder value) {
            if (connection != this) return;
            binder = value;
            final IShizukuClipboard service = IShizukuClipboard.Stub.asInterface(value);
            remote = service;
            try { binder.linkToDeath(this, 0); linked = true; }
            catch (RemoteException gone) { failed(); return; }
            io.execute(new Runnable() {
                @Override public void run() {
                    try {
                        service.registerListener(callback);
                    } catch (RemoteException | RuntimeException error) {
                        main.post(new Runnable() { @Override public void run() { if (connection == Connection.this) failed(); } });
                    }
                }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { binderDied(); }
        @Override public void binderDied() {
            main.post(new Runnable() { @Override public void run() { if (connection == Connection.this) failed(); } });
        }
    }
}
