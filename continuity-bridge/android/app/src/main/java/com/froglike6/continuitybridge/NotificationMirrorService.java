package com.froglike6.continuitybridge;

import android.app.Notification;
import android.app.NotificationManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

public final class NotificationMirrorService extends NotificationListenerService {
    private HandlerThread captureThread;
    private Handler captureHandler;
    private NotificationExtractor extractor;
    private NotificationPreferences preferences;
    private final Set<String> progressKeys = new LinkedHashSet<>();

    @Override public void onCreate() {
        super.onCreate();
        extractor = new NotificationExtractor(this);
        preferences = new NotificationPreferences(this);
        captureThread = new HandlerThread("continuity-notifications");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
    }

    @Override public void onDestroy() {
        captureThread.quitSafely();
        super.onDestroy();
    }

    @Override public void onListenerConnected() { super.onListenerConnected(); new ConfigStore(this).listenerState(NotificationListenerState.CONNECTED); }
    @Override public void onListenerDisconnected() { new ConfigStore(this).listenerState(NotificationListenerState.DISCONNECTED); super.onListenerDisconnected(); }

    @Override public void onNotificationPosted(final StatusBarNotification sbn) {
        String source = sbn.getPackageName();
        if (getPackageName().equals(source) || "android".equals(source) || "com.android.systemui".equals(source)) return;
        if (!preferences.allows(source)) return;
        Ranking ranking = new Ranking();
        RankingMap rankings = getCurrentRanking();
        final int importance = rankings != null && rankings.getRanking(sbn.getKey(), ranking)
                ? ranking.getImportance() : NotificationManager.IMPORTANCE_DEFAULT;
        captureHandler.post(new Runnable() {
            @Override public void run() { capture(sbn, importance); }
        });
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        captureHandler.post(new Runnable() { @Override public void run() { progressKeys.remove(sbn.getKey()); } });
    }

    private void capture(StatusBarNotification sbn, int importance) {
        try {
            if (!preferences.allows(sbn.getPackageName())) { progressKeys.remove(sbn.getKey()); return; }
            NotificationFields fields = extractor.extract(sbn);
            boolean groupSummary = (sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0;
            if (NotificationMapper.excludeContentlessGroupSummary(groupSummary, fields)) return;
            boolean foreground = (sbn.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
            if (!NotificationDeliveryPolicy.shouldMirror(fields.getCategory(), sbn.isOngoing(), foreground, importance,
                    fields.getProgress() != null, progressKeys.contains(sbn.getKey()))) return;
            boolean saved = BridgeRepository.get(this).outbox().enqueueNotification(fields, System.currentTimeMillis());
            if (saved) {
                if (fields.getProgress() != null && sbn.isOngoing()) {
                    if (progressKeys.size() >= 256) progressKeys.remove(progressKeys.iterator().next());
                    progressKeys.add(sbn.getKey());
                } else progressKeys.remove(sbn.getKey());
            }
            new ConfigStore(this).notificationDeliveryStatus(saved ? "전송 대기 저장됨" : "내구 큐 저장 실패");
        } catch (IOException error) { new ConfigStore(this).notificationDeliveryStatus("내구 큐 저장 실패"); }
        catch (RuntimeException error) { new ConfigStore(this).notificationDeliveryStatus("알림 내용을 읽을 수 없음"); }
    }
}
