package com.froglike6.continuitybridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.PairingConnectionCtx;

final class LocalAdbOperations {
    interface Gate { boolean active(); }
    static final class NeedsApprovalException extends IOException {
        private static final long serialVersionUID = 1L;
        NeedsApprovalException() { super("adb_network_approval_required"); }
    }
    private static final ScheduledExecutorService DEADLINES = Executors.newScheduledThreadPool(2);
    private final Context context;
    private final LocalAdbIdentity identity;
    private volatile Call current;
    LocalAdbOperations(Context context) { this.context = context; identity = new LocalAdbIdentity(context); }

    void cancel() { Call call = current; if (call != null) call.cancel(); }

    void pair(int port, String code, Gate gate) throws Exception {
        if (port < 0 || port > 65_535 || code == null || !code.matches("[0-9]{6}"))
            throw new IllegalArgumentException("pairing_input_invalid");
        Call call = new Call(gate);
        current = call;
        try {
            call.check();
            if (port == 0) port = LocalAdbDiscovery.find(context, LocalAdbDiscovery.PAIRING);
            call.check();
            LocalAdbIdentity.Identity key = identity.load();
            try (PairingConnectionCtx pairing = new PairingConnectionCtx("127.0.0.1", port,
                code.getBytes(StandardCharsets.UTF_8), key.privateKey, key.certificate, "Continuity Bridge")) {
                call.abort = new Runnable() { @Override public void run() { pairing.cancel(); } };
                call.check();
                pairing.start();
                call.check();
            }
        } finally { finish(call); }
    }

    void start(String nonce, boolean automatic, Gate gate) throws Exception {
        if (automatic && Build.VERSION.SDK_INT < 33) throw new IllegalArgumentException("automatic_recovery_unsupported");
        Call call = new Call(gate);
        current = call;
        try {
        call.check();
        boolean enabledWireless = false;
        if (automatic && context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            call.check();
            Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            call.check();
            Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", 1);
            enabledWireless = true;
        }
        int port;
        try { port = LocalAdbDiscovery.find(context, LocalAdbDiscovery.CONNECT); }
        catch (IOException missing) {
            if (enabledWireless && Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 0)
                throw new NeedsApprovalException();
            throw missing;
        }
        call.check();
        LocalAdbIdentity.Identity key = identity.load();
        try (AdbConnection connection = new AdbConnection.Builder("127.0.0.1", port)
                .setApi(Build.VERSION.SDK_INT).setPrivateKey(key.privateKey).setCertificate(key.certificate)
                .setDeviceName("Continuity Bridge").build()) {
            call.abort = new Runnable() {
                @Override public void run() {
                    try { connection.close(); }
                    catch (IOException error) { android.util.Log.w("EmbeddedClipboard", "adb_deadline_close_failed"); }
                }
            };
                call.check();
                if (!connection.connect(8, TimeUnit.SECONDS, true)) throw new IOException("adb_connect_timeout");
                call.check();
                if (automatic && context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                    String result = command(connection, "pm grant --user 0 com.froglike6.continuitybridge android.permission.WRITE_SECURE_SETTINGS && printf CB_AUTO_GRANTED", "CB_AUTO_GRANTED");
                    if (!result.contains("CB_AUTO_GRANTED")
                            || context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED)
                        throw new IOException("automatic_recovery_denied");
                }
                call.check();
                String apk = context.getApplicationInfo().sourceDir;
                String launch = "trap '' HUP; CLASSPATH=" + quote(apk)
                        + " /system/bin/app_process /system/bin --nice-name=continuity_bridge_helper "
                        + "com.froglike6.continuitybridge.ClipboardHelperMain " + quote(nonce)
                        + " </dev/null >/dev/null 2>&1 &\nprintf CB_HELPER_STARTED";
                command(connection, launch, "CB_HELPER_STARTED");
                call.check();
        }
        } finally { finish(call); }
    }

    private void finish(Call call) {
        call.finish();
        if (current == call) current = null;
    }

    private static final class Call {
        final Gate gate;
        final Thread thread = Thread.currentThread();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final ScheduledFuture<?> deadline;
        volatile Runnable abort;
        boolean finished;
        Call(Gate gate) {
            this.gate = gate;
            deadline = DEADLINES.schedule(new Runnable() { @Override public void run() { cancel(); } }, 30, TimeUnit.SECONDS);
        }
        void check() throws InterruptedException {
            if (cancelled.get() || !gate.active() || Thread.currentThread().isInterrupted())
                throw new InterruptedException("adb_operation_cancelled");
        }
        synchronized void cancel() {
            if (finished || !cancelled.compareAndSet(false, true)) return;
            thread.interrupt();
            final Runnable action = abort;
            if (action != null) DEADLINES.execute(action);
        }
        synchronized void finish() {
            finished = true;
            deadline.cancel(false);
            abort = null;
            if (cancelled.get()) Thread.interrupted();
        }
    }

    private static String command(AdbConnection connection, String command, String marker) throws Exception {
        try (AdbStream stream = connection.open("shell:" + command);
                InputStream input = stream.openInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[1024];
            int count;
            while ((count = input.read(bytes)) != -1) {
                if (output.size() + count > 16_384) throw new IOException("adb_response_too_large");
                output.write(bytes, 0, count);
                String response = new String(output.toByteArray(), StandardCharsets.UTF_8);
                if (response.contains(marker)) return response;
            }
            throw new IOException("adb_command_incomplete");
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
