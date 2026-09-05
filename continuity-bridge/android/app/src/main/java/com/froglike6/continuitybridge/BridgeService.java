package com.froglike6.continuitybridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.util.Random;

public final class BridgeService extends Service {
    static final String CHANNEL_ID = "continuity_bridge_status";
    static final int STATUS_NOTIFICATION_ID = 3103;
    private final ServiceRunCoordinator runs = new ServiceRunCoordinator();
    private volatile Worker activeWorker;

    @Override public void onCreate() {
        super.onCreate(); createChannel(); startForeground(STATUS_NOTIFICATION_ID, notification(ConnectionStatus.DISCONNECTED));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ServiceRunCoordinator.Run run = runs.start();
        if (run != null) {
            Worker worker = new Worker(run); activeWorker = worker; worker.thread.start();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        Worker worker = activeWorker;
        if (worker != null && runs.cancel(worker.run)) worker.cancel();
        if (activeWorker == worker) activeWorker = null;
        setStatus(ConnectionStatus.STOPPED, null); super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void connectLoop(Worker worker) {
        ConfigStore config = new ConfigStore(this); RetryPolicy retry = new RetryPolicy(1_000, 60_000, new Random()); int attempt = 0;
        try {
            ConnectionOwner owner = new ConnectionOwner(); RelayTransport transport = new RelayTransport(this, config);
            BridgeRepository repository = BridgeRepository.get(this); FileBridgeStateStore state = repository.store();
            AndroidClipboardApplier applier = new AndroidClipboardApplier(getSystemService(android.content.ClipboardManager.class), state);
            BridgeEngine engine = new BridgeEngine(state, transport, new TokenStore(this), applier, owner);
            ConnectionOwner.Lease lease = owner.start(); ClipboardCaptureController clipboardCapture = new ClipboardCaptureController(this, repository.outbox());
            if (!worker.attach(engine, lease, clipboardCapture)) return;
            clipboardCapture.start();
            BridgeEngine.Status previous = null;
            while (runs.owns(worker.run) && !Thread.currentThread().isInterrupted()) {
                if (ConnectionPresentation.showConnectingBeforeAttempt(previous)
                        && !publishStatus(worker.run, ConnectionStatus.CONNECTING, null)) return;
                BridgeEngine.Result result = engine.step(lease); previous = result.status();
                if (!publishStatus(worker.run, status(previous), result.errorClass())) return;
                if (result.status() == BridgeEngine.Status.CONNECTED) { attempt = 0; continue; }
                if (result.status() == BridgeEngine.Status.RETRY) { pause(retry.delayMs(attempt++)); continue; }
                return;
            }
        } catch (Exception error) {
            publishStatus(worker.run, ConnectionStatus.SECURITY_FAILURE, error.getClass().getSimpleName());
        } finally {
            if (runs.finish(worker.run)) worker.close();
            if (activeWorker == worker) activeWorker = null;
        }
    }

    private final class Worker {
        final ServiceRunCoordinator.Run run;
        final Thread thread;
        private BridgeEngine engine;
        private ConnectionOwner.Lease lease;
        private ClipboardCaptureController capture;
        private boolean cancelled;

        Worker(ServiceRunCoordinator.Run run) {
            this.run = run;
            thread = new Thread(new Runnable() { @Override public void run() { connectLoop(Worker.this); } }, "continuity-relay-connection");
        }

        synchronized boolean attach(BridgeEngine engine, ConnectionOwner.Lease lease, ClipboardCaptureController capture) {
            if (cancelled) { cancelEngine(engine, lease); return false; }
            this.engine = engine; this.lease = lease; this.capture = capture; return true;
        }

        synchronized void cancel() { cancelled = true; close(); thread.interrupt(); }

        synchronized void close() {
            if (capture != null) { capture.stop(); capture = null; }
            if (engine != null && lease != null) { cancelEngine(engine, lease); engine = null; lease = null; }
        }
    }

    private static ConnectionStatus status(BridgeEngine.Status status) {
        switch (status) {
            case CONNECTED: return ConnectionStatus.CONNECTED;
            case PERMISSION_REQUIRED: return ConnectionStatus.PERMISSION_REQUIRED;
            case AUTH_FAILURE: return ConnectionStatus.AUTH_FAILURE;
            case TLS_FAILURE: return ConnectionStatus.TLS_FAILURE;
            case SECURITY_FAILURE: return ConnectionStatus.SECURITY_FAILURE;
            case RETRY: return ConnectionStatus.RETRY;
            case STOPPED: return ConnectionStatus.STOPPED;
            case PROTOCOL_FAILURE: return ConnectionStatus.DISCONNECTED;
            default: throw new IllegalStateException("unknown_engine_status");
        }
    }

    private void pause(long milliseconds) { try { Thread.sleep(milliseconds); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } }
    private static void cancelEngine(BridgeEngine engine, ConnectionOwner.Lease lease) { engine.cancel(lease); }
    private boolean publishStatus(ServiceRunCoordinator.Run run, final ConnectionStatus status, final String errorClass) {
        return runs.publish(run, new Runnable() { @Override public void run() { setStatus(status, errorClass); } });
    }
    private void setStatus(ConnectionStatus status, String errorClass) {
        new ConfigStore(this).status(status); MetadataLog.state(status, errorClass);
        NotificationManager manager = getSystemService(NotificationManager.class); manager.notify(STATUS_NOTIFICATION_ID, notification(status));
    }
    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "연속성 브리지 상태", NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(ConnectionStatus status) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("연속성 브리지").setContentText(status.korean()).setContentIntent(pending).setOngoing(true).build();
    }
}
