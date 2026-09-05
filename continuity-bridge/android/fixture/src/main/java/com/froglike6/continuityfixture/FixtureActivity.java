package com.froglike6.continuityfixture;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class FixtureActivity extends Activity {
    private static final String CHANNEL = "continuity_fixture";
    private static final int NOTIFICATION_ID = 7301;
    private static final long FOREGROUND_PIPELINE_DELAY_MS = 1500L;
    private static final String PRODUCT_PACKAGE = "com.froglike6.continuitybridge";
    private static final String PRODUCT_MAIN_ACTIVITY = "com.froglike6.continuitybridge.MainActivity";
    private static final Handler FOREGROUND_PIPELINE_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile PipelineObservation pipelineObservation;
    private EditText input;
    private TextView result;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setTitle("Continuity Fixture");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Continuity Fixture", NotificationManager.IMPORTANCE_DEFAULT));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24), dp(24), dp(24), dp(24));
        input = new EditText(this); input.setText("CLIP_ANDROID_TO_MAC_1"); input.setSingleLine(false); input.setMinHeight(dp(48)); content.addView(input);
        addButton(content, "클립보드 설정", new View.OnClickListener() { @Override public void onClick(View view) { setClipboard(); } });
        addButton(content, "AVD 전경 파이프라인 테스트", new View.OnClickListener() { @Override public void onClick(View view) { scheduleForegroundPipeline(); } });
        addButton(content, "클립보드 읽기", new View.OnClickListener() { @Override public void onClick(View view) { readClipboard(); } });
        addButton(content, "알림 게시", new View.OnClickListener() { @Override public void onClick(View view) { post(false); } });
        addButton(content, "같은 알림 업데이트", new View.OnClickListener() { @Override public void onClick(View view) { post(true); } });
        result = new TextView(this); result.setText("준비됨"); result.setTextIsSelectable(true); content.addView(result); setContentView(content);
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
    }

    private void setClipboard() {
        writeClipboard(input.getText().toString()); result.setText("클립보드 설정 완료");
    }
    private void scheduleForegroundPipeline() {
        final String text = input.getText().toString();
        result.setText("전경 파이프라인 예약됨");
        Intent product = new Intent();
        product.setClassName(PRODUCT_PACKAGE, PRODUCT_MAIN_ACTIVITY);
        startActivity(product);
        FOREGROUND_PIPELINE_HANDLER.postDelayed(new PipelineClipboardWrite(getApplicationContext(), text), FOREGROUND_PIPELINE_DELAY_MS);
    }
    private void writeClipboard(String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Continuity Fixture", text));
    }
    private void readClipboard() {
        ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText(); result.setText(text == null ? "읽을 텍스트 없음" : text);
    }
    private void post(boolean update) {
        String suffix = update ? " 업데이트" : "";
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Fixture title" + suffix).setContentText("Fixture body" + suffix)
                .setStyle(new Notification.BigTextStyle().bigText("Fixture big body" + suffix)).build();
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification); result.setText(update ? "알림 업데이트 완료" : "알림 게시 완료");
    }
    static final class PipelineObservation {
        interface Sink {
            void record(String observationId, String phase);
        }

        interface IdSource {
            String next();
        }

        private final Sink sink;
        private final IdSource ids;
        private final Set<String> issued = new HashSet<>();

        PipelineObservation(Sink sink, IdSource ids) {
            this.sink = sink;
            this.ids = ids;
        }

        synchronized Action begin() {
            String id = ids.next();
            if (id == null || id.isEmpty() || !issued.add(id)) throw new IllegalStateException("invalid observation id");
            sink.record(id, "scheduled");
            return new Action(id, sink);
        }

        static final class Action {
            private final String id;
            private final Sink sink;
            private int state;

            private Action(String id, Sink sink) {
                this.id = id;
                this.sink = sink;
            }

            synchronized void runnableEntered() {
                requireState(0);
                sink.record(id, "runnable_entered");
                state = 1;
            }

            synchronized void writeReturned() {
                terminal("write_returned");
            }

            synchronized void writeFailed(RuntimeException failure) {
                terminal("write_failed:" + failure.getClass().getName());
            }

            private void terminal(String phase) {
                requireState(1);
                sink.record(id, phase);
                state = 2;
            }

            private void requireState(int expected) {
                if (state != expected) throw new IllegalStateException("invalid observation order");
            }
        }
    }

    private static final class PipelineClipboardWrite implements Runnable {
        private final Context applicationContext;
        private final String text;
        private final PipelineObservation.Action observation;

        PipelineClipboardWrite(Context context, String text) {
            applicationContext = context.getApplicationContext();
            this.text = text;
            observation = observationJournal(applicationContext).begin();
        }

        @Override public void run() {
            observation.runnableEntered();
            try {
                applicationContext.getSystemService(ClipboardManager.class)
                        .setPrimaryClip(ClipData.newPlainText("Continuity Fixture", text));
                observation.writeReturned();
            } catch (RuntimeException failure) {
                observation.writeFailed(failure);
                throw failure;
            }
        }
    }

    private static PipelineObservation observationJournal(Context context) {
        PipelineObservation current = pipelineObservation;
        if (current != null) return current;
        synchronized (FixtureActivity.class) {
            if (pipelineObservation == null) {
                Context applicationContext = context.getApplicationContext();
                pipelineObservation = new PipelineObservation(
                        new PreferenceReceiptSink(applicationContext), new PipelineObservation.IdSource() {
                            @Override public String next() { return UUID.randomUUID().toString(); }
                        });
            }
            return pipelineObservation;
        }
    }

    private static final class PreferenceReceiptSink implements PipelineObservation.Sink {
        private static final String PREFERENCES = "pipeline_diagnostics";
        private static final String TAG = "ContinuityFixture";
        private final SharedPreferences preferences;

        PreferenceReceiptSink(Context context) {
            preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        }

        @Override public synchronized void record(String observationId, String phase) {
            String previousId = preferences.getString("observation_id", "");
            String previousHistory = preferences.getString("phase_history", "");
            String history = observationId.equals(previousId) && !previousHistory.isEmpty()
                    ? previousHistory + "," + phase : phase;
            boolean committed = preferences.edit()
                    .putString("observation_id", observationId)
                    .putString("phase_history", history)
                    .commit();
            if (!committed) throw new IllegalStateException("diagnostic receipt commit failed");
            Log.i(TAG, "event=pipeline.receipt observationId=" + observationId + " phase=" + phase);
        }
    }
    private void addButton(LinearLayout parent, String text, View.OnClickListener listener) { Button button = new Button(this); button.setText(text); button.setMinHeight(dp(48)); button.setOnClickListener(listener); parent.addView(button); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
