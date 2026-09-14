package com.froglike6.continuityfixture;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class NotificationFixtureReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.froglike6.continuityfixture.POST_NOTIFICATION";
    private static final String CHANNEL = "notification_capture_scenarios";
    private static final String CHARGING_CHANNEL = "notification_charging_status_scenarios";
    private static final String BACKGROUND_CHANNEL = "notification_background_status_scenarios";
    private static final String QUIET_LOW_CHANNEL = "notification_quiet_low_scenarios";
    private static final String QUIET_MINIMAL_CHANNEL = "notification_quiet_minimal_scenarios";
    private static final String DOWNLOAD_LOW_CHANNEL = "notification_download_low_scenarios";
    private static final String TAG = "notification-fixture";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        String mode = intent.getStringExtra("mode");
        if (mode == null) mode = "ordinary";
        int id = intent.getIntExtra("id", 7401);
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel(mode));
            if ("remove".equals(mode)) manager.cancel(TAG, id);
            else manager.notify(TAG, id, notification(context, mode, intent));
            setResultCode(Activity.RESULT_OK);
            setResultData(mode + ":" + id);
            Log.i("ContinuityFixture", "event=notification.post mode=" + mode + " id=" + id);
        } catch (IllegalArgumentException | SecurityException failure) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData(failure.getClass().getSimpleName());
        }
    }

    static Notification notification(Context context, String mode, Intent intent) {
        String token = intent.getStringExtra("token");
        String prefix = token == null || token.isEmpty() ? "" : token.substring(0, Math.min(token.length(), 256)) + " ";
        Notification.Builder builder = new Notification.Builder(context, channel(mode).getId())
                .setSmallIcon(context.getApplicationInfo().icon).setOnlyAlertOnce(true)
                .setContentTitle("Fixture title").setContentText(prefix + "Ordinary body");
        switch (mode) {
            case "ordinary": break;
            case "private":
                builder.setVisibility(Notification.VISIBILITY_PRIVATE).setContentTitle("Private message")
                        .setContentText(prefix + "Readable private body");
                break;
            case "messaging":
                builder.setCategory(Notification.CATEGORY_MESSAGE).setStyle(new Notification.MessagingStyle(new Person.Builder().setName("Me").build())
                        .setConversationTitle("Fixture conversation").setGroupConversation(true)
                        .addMessage(prefix + "First message", 1, new Person.Builder().setName("Alice").build())
                        .addMessage(prefix + "Second message", 2, new Person.Builder().setName("Bob").build()));
                break;
            case "inbox":
                builder.setContentText("Two updates").setStyle(new Notification.InboxStyle()
                        .addLine(prefix + "First line").addLine(prefix + "Second line"));
                break;
            case "empty-bigtext":
                builder.setContentText(prefix + "Normal body survives empty bigText").setStyle(new Notification.BigTextStyle().bigText(""));
                break;
            case "indeterminate":
                builder.setContentTitle("Preparing download").setContentText(prefix + "Waiting for connection")
                        .setCategory(Notification.CATEGORY_PROGRESS).setProgress(0, 0, true).setOngoing(true);
                break;
            case "progress":
            case "download-low-progress":
                int progress = intent.getIntExtra("progress", 37);
                if (progress < 0 || progress > 100) throw new IllegalArgumentException("invalid progress");
                builder.setContentTitle("Downloading file").setContentText(prefix + progress + "%")
                        .setCategory(Notification.CATEGORY_PROGRESS).setProgress(100, progress, false).setOngoing(true);
                break;
            case "progress-text":
                builder.setContentTitle("Downloading file").setContentText(prefix + "37%")
                        .setCategory(Notification.CATEGORY_PROGRESS).setOngoing(true);
                break;
            case "complete":
            case "download-low-complete":
                builder.setContentTitle("Download complete").setContentText(prefix + "File saved")
                        .setCategory(Notification.CATEGORY_PROGRESS).setProgress(100, 100, false).setOngoing(false);
                break;
            case "failure":
            case "download-low-failure":
                builder.setContentTitle("Download failed").setContentText(prefix + "Connection interrupted")
                        .setCategory(Notification.CATEGORY_ERROR).setOngoing(false);
                break;
            case "charging-percent":
            case "charging-progress":
                int battery = intent.getIntExtra("progress", 68);
                if (battery < 0 || battery > 100) throw new IllegalArgumentException("invalid progress");
                builder.setContentTitle("Charging").setContentText(prefix + battery + "% battery")
                        .setCategory(Notification.CATEGORY_STATUS).setOngoing(true);
                if ("charging-progress".equals(mode)) builder.setProgress(100, battery, false);
                break;
            case "background-service":
                builder.setContentTitle("Background service").setContentText(prefix + "Service is running")
                        .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true);
                break;
            case "background-status":
                builder.setContentTitle("Background status").setContentText(prefix + "Service remains connected")
                        .setCategory(Notification.CATEGORY_STATUS).setOngoing(true);
                break;
            case "quiet-low":
                builder.setContentTitle("Quiet notice").setContentText(prefix + "Background notice");
                break;
            case "quiet-minimal":
                builder.setContentTitle("Device status").setContentText(prefix + "Passive status notice")
                        .setCategory(Notification.CATEGORY_STATUS);
                break;
            case "redacted":
                builder.setContentTitle(context.getApplicationInfo().loadLabel(context.getPackageManager()))
                        .setContentText(redactionMarker(context)).setVisibility(Notification.VISIBILITY_PRIVATE);
                break;
            case "redacted-messaging":
                Person empty = new Person.Builder().setName("").build();
                builder.setStyle(new Notification.MessagingStyle(empty).addMessage(redactionMarker(context), 1, empty));
                break;
            default: throw new IllegalArgumentException("unknown notification scenario");
        }
        return builder.build();
    }

    private static NotificationChannel channel(String mode) {
        switch (mode) {
            case "charging-percent":
            case "charging-progress":
                return new NotificationChannel(CHARGING_CHANNEL, "Charging status scenarios", NotificationManager.IMPORTANCE_DEFAULT);
            case "background-service":
            case "background-status":
                return new NotificationChannel(BACKGROUND_CHANNEL, "Background status scenarios", NotificationManager.IMPORTANCE_DEFAULT);
            case "quiet-low":
                return new NotificationChannel(QUIET_LOW_CHANNEL, "Quiet notice scenarios", NotificationManager.IMPORTANCE_LOW);
            case "quiet-minimal":
                return new NotificationChannel(QUIET_MINIMAL_CHANNEL, "Minimal status scenarios", NotificationManager.IMPORTANCE_MIN);
            case "download-low-progress":
            case "download-low-complete":
            case "download-low-failure":
                return new NotificationChannel(DOWNLOAD_LOW_CHANNEL, "Quiet download scenarios", NotificationManager.IMPORTANCE_LOW);
            default:
                return new NotificationChannel(CHANNEL, "Notification capture scenarios", NotificationManager.IMPORTANCE_DEFAULT);
        }
    }

    private static String redactionMarker(Context context) {
        int identifier = context.getResources().getIdentifier("redacted_notification_message", "string", "android");
        if (identifier == 0) throw new IllegalArgumentException("framework redaction marker unavailable");
        return context.getString(identifier);
    }
}
