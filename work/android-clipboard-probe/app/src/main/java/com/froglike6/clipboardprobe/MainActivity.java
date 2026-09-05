package com.froglike6.clipboardprobe;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class MainActivity extends Activity {
    private static final String CHANNEL_ID = "clipboard_probe";
    private static final String LOG_TAG = "ClipboardProbe";
    private static final long TRIAL_DURATION_MS = 60_000L;
    private static final int CONTROL_NOTIFICATION_ID_BASE = 1100;
    private static final int OUTCOME_NOTIFICATION_ID_BASE = 2100;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ClipboardManager.OnPrimaryClipChangedListener clipboardListener =
            new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    handlePrimaryClipChanged();
                }
            };
    private final Runnable timeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (trialActive && trialLifecycle.listenerIsRegistered()) {
                completeTrial(trialLifecycle.timeoutOutcome(), ClipObservation.unreadable());
            }
        }
    };

    private ClipboardManager clipboardManager;
    private NotificationManager notificationManager;
    private Button startButton;
    private TextView statusView;
    private TextView tokenView;
    private boolean hasFocus;
    private boolean listenerRegistered;
    private boolean trialActive;
    private boolean backgroundArmed;
    private final TrialLifecycle trialLifecycle = new TrialLifecycle();
    private int nextTrial = 1;
    private int activeTrial;
    private long deadlineElapsedMs;
    private long successfulControlClipTimestamp;
    private String expectedControlToken;
    private String expectedBackgroundToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        statusView = new TextView(this);
        statusView.setText("Ready. Start Trial 1 while this app has focus.");
        tokenView = new TextView(this);
        tokenView.setText("The background token appears only after the foreground control succeeds.");
        startButton = new Button(this);
        startButton.setText("Start Trial 1");
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startTrial();
            }
        });
        layout.addView(statusView);
        layout.addView(tokenView);
        layout.addView(startButton);
        setContentView(layout);
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        hasFocus = focused;
    }

    @Override
    protected void onDestroy() {
        clearTrialResources();
        super.onDestroy();
    }

    private void startTrial() {
        if (trialActive || nextTrial > 3) {
            return;
        }
        if (!notificationsAllowed()) {
            requestNotificationPermission();
            return;
        }
        activeTrial = nextTrial;
        nextTrial += 1;
        expectedControlToken = "CONTROL_" + activeTrial;
        expectedBackgroundToken = "CLIP_PROBE_BG_" + activeTrial;
        successfulControlClipTimestamp = 0L;
        trialActive = true;
        backgroundArmed = false;
        trialLifecycle.begin();
        startButton.setEnabled(false);
        statusView.setText("Trial " + activeTrial + " is proving foreground control.");
        tokenView.setText("Waiting for foreground control callback and notification.");
        registerListener();
        scheduleDeadline();
        try {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("clipboard-probe-control",
                    expectedControlToken));
        } catch (SecurityException exception) {
            completeTrial(VerdictClassifier.Outcome.SECURITY_EXCEPTION, ClipObservation.unreadable());
        }
    }

    private void handlePrimaryClipChanged() {
        if (!trialActive) {
            return;
        }
        ClipObservation observation;
        try {
            observation = readPrimaryClip();
        } catch (SecurityException exception) {
            completeTrial(VerdictClassifier.Outcome.SECURITY_EXCEPTION, ClipObservation.unreadable());
            return;
        }
        if (!backgroundArmed) {
            if (hasFocus && observation.matches(expectedControlToken)) {
                successfulControlClipTimestamp = observation.clipTimestamp > 0L
                        ? observation.clipTimestamp : 0L;
                writeOutcome(VerdictClassifier.Outcome.CONTROL_CALLBACK_OK, observation, 0);
                postControlNotification(observation);
            }
            return;
        }
        if (!observation.readable) {
            completeTrial(VerdictClassifier.Outcome.BACKGROUND_CALLBACK_READ_BLOCKED, observation);
        } else {
            VerdictClassifier.Outcome outcome = VerdictClassifier.classifyReadableBackgroundClip(
                    observation.value, observation.clipTimestamp, expectedControlToken,
                    successfulControlClipTimestamp, expectedBackgroundToken);
            if (outcome == VerdictClassifier.Outcome.CONTROL_DUPLICATE_IGNORED) {
                writeOutcome(outcome, observation, 0);
                return;
            }
            if (outcome == VerdictClassifier.Outcome.BACKGROUND_CALLBACK_READ_OK) {
                observation.matches(expectedBackgroundToken);
            }
            completeTrial(outcome, observation);
        }
    }

    private void postControlNotification(ClipObservation observation) {
        int notificationId = CONTROL_NOTIFICATION_ID_BASE + activeTrial;
        try {
            notificationManager.notify(notificationId, notificationFor("Control ready", activeTrial));
        } catch (SecurityException exception) {
            completeTrial(VerdictClassifier.Outcome.SECURITY_EXCEPTION, observation);
            return;
        }
        writeOutcome(VerdictClassifier.Outcome.CONTROL_NOTIFICATION_OK, observation, notificationId);
        handler.removeCallbacks(timeoutRunnable);
        trialLifecycle.armBackgroundDeadline();
        backgroundArmed = true;
        scheduleDeadline();
        statusView.setText("Trial " + activeTrial + " armed for 60 seconds. Leave this app in background, "
                + "then copy the displayed token once in Samsung Notes.");
        tokenView.setText(expectedBackgroundToken);
    }

    private void completeTrial(VerdictClassifier.Outcome outcome, ClipObservation observation) {
        if (!trialActive) {
            return;
        }
        int completedTrial = activeTrial;
        trialActive = false;
        backgroundArmed = false;
        handler.removeCallbacks(timeoutRunnable);
        unregisterListener();
        int notificationId = OUTCOME_NOTIFICATION_ID_BASE + completedTrial;
        try {
            notificationManager.notify(notificationId, notificationFor("Trial result", completedTrial));
        } catch (SecurityException exception) {
            statusView.setText("Trial " + completedTrial
                    + " finished, but its result notification could not be posted.");
        }
        writeOutcome(outcome, observation, notificationId);
        if (nextTrial > 3) {
            statusView.setText("All three trials are complete. Review the structured log evidence.");
            tokenView.setText("No further trials can be armed.");
            startButton.setEnabled(false);
            startButton.setText("Trials complete");
        } else {
            statusView.setText("Trial " + completedTrial + " finished: " + outcome.name()
                    + ". Return here and start Trial " + nextTrial + ".");
            tokenView.setText("No clipboard content is retained by this app.");
            startButton.setEnabled(true);
            startButton.setText("Start Trial " + nextTrial);
        }
        successfulControlClipTimestamp = 0L;
    }

    private ClipObservation readPrimaryClip() {
        ClipData clip = clipboardManager.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return ClipObservation.unreadable();
        }
        ClipDescription description = clip.getDescription();
        ClipData.Item item = clip.getItemAt(0);
        CharSequence text = item == null ? null : item.getText();
        if (text == null) {
            return ClipObservation.unreadable();
        }
        String value = text.toString();
        long clipTimestamp = description == null ? 0L : description.getTimestamp();
        return ClipObservation.readable(value.length(), sha256(value), description != null,
                clipTimestamp, value);
    }

    private void registerListener() {
        if (!listenerRegistered) {
            clipboardManager.addPrimaryClipChangedListener(clipboardListener);
            listenerRegistered = true;
            trialLifecycle.listenerRegistered();
        }
    }

    private void unregisterListener() {
        if (listenerRegistered) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener);
            listenerRegistered = false;
        }
        trialLifecycle.terminalCleanup();
    }

    private void scheduleDeadline() {
        deadlineElapsedMs = SystemClock.elapsedRealtime() + TRIAL_DURATION_MS;
        handler.postDelayed(timeoutRunnable, TRIAL_DURATION_MS);
    }

    private void clearTrialResources() {
        handler.removeCallbacks(timeoutRunnable);
        unregisterListener();
        trialActive = false;
        backgroundArmed = false;
        successfulControlClipTimestamp = 0L;
    }

    private boolean notificationsAllowed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (!notificationManager.areNotificationsEnabled()) {
            return false;
        }
        NotificationChannel channel = notificationManager.getNotificationChannel(CHANNEL_ID);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            statusView.setText("Notification permission is required. Grant it, then start the trial again.");
        } else {
            statusView.setText("Notifications are blocked. Enable this app's notification channel, then retry.");
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Clipboard Probe",
                    NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification notificationFor(String title, int trial) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText("Clipboard probe trial " + trial)
                .setAutoCancel(true)
                .build();
    }

    private void writeOutcome(VerdictClassifier.Outcome outcome, ClipObservation observation,
            int notificationId) {
        Log.i(LOG_TAG, "event=" + outcome.name()
                + " trial=" + activeTrial
                + " elapsedMs=" + SystemClock.elapsedRealtime()
                + " focused=" + hasFocus
                + " armed=" + backgroundArmed
                + " readable=" + observation.readable
                + " exactMatch=" + observation.exactMatch
                + " descriptionPresent=" + observation.descriptionPresent
                + " clipTimestamp=" + observation.clipTimestamp
                + " controlClipTimestamp=" + successfulControlClipTimestamp
                + " length=" + observation.length
                + " sha256=" + observation.sha256
                + " notificationId=" + notificationId
                + " deadlineElapsedMs=" + deadlineElapsedMs);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                encoded.append(String.format("%02x", item & 0xff));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException exception) {
            return "unavailable";
        }
    }

    private static final class ClipObservation {
        final boolean readable;
        final int length;
        final String sha256;
        final boolean descriptionPresent;
        final long clipTimestamp;
        final String value;
        boolean exactMatch;

        private ClipObservation(boolean readable, int length, String sha256, boolean descriptionPresent,
                long clipTimestamp, String value) {
            this.readable = readable;
            this.length = length;
            this.sha256 = sha256;
            this.descriptionPresent = descriptionPresent;
            this.clipTimestamp = clipTimestamp;
            this.value = value;
        }

        static ClipObservation unreadable() {
            return new ClipObservation(false, -1, "none", false, 0L, null);
        }

        static ClipObservation readable(int length, String sha256, boolean descriptionPresent,
                long clipTimestamp, String value) {
            return new ClipObservation(true, length, sha256, descriptionPresent, clipTimestamp, value);
        }

        boolean matches(String expectedValue) {
            exactMatch = readable && expectedValue != null && expectedValue.equals(value);
            return exactMatch;
        }
    }
}
