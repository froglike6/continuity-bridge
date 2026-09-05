package com.froglike6.continuitybridge;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.io.IOException;

public final class NotificationMirrorService extends NotificationListenerService {
    @Override public void onListenerConnected() { super.onListenerConnected(); new ConfigStore(this).listenerState(NotificationListenerState.CONNECTED); }
    @Override public void onListenerDisconnected() { new ConfigStore(this).listenerState(NotificationListenerState.DISCONNECTED); super.onListenerDisconnected(); }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (NotificationMapper.excludeForeground(sbn.getPackageName(), notification.getChannelId(), sbn.getId(),
                getPackageName(), BridgeService.CHANNEL_ID, BridgeService.STATUS_NOTIFICATION_ID)) return;
        Bundle extras = notification.extras;
        String label = "";
        try { ApplicationInfo info = getPackageManager().getApplicationInfo(sbn.getPackageName(), 0); label = String.valueOf(getPackageManager().getApplicationLabel(info)); }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        NotificationFields fields = NotificationMapper.map(sbn.getKey(), sbn.getPackageName(), label,
                value(extras, Notification.EXTRA_TITLE), value(extras, Notification.EXTRA_TEXT), value(extras, Notification.EXTRA_BIG_TEXT), sbn.getPostTime());
        try {
            boolean saved = BridgeRepository.get(this).outbox().enqueueNotification(fields, System.currentTimeMillis());
            new ConfigStore(this).notificationDeliveryStatus(saved ? "전송 대기 저장됨" : "내구 큐 저장 실패");
        } catch (IOException error) { new ConfigStore(this).notificationDeliveryStatus("내구 큐 저장 실패"); }
    }

    private static String value(Bundle extras, String key) { CharSequence value = extras == null ? null : extras.getCharSequence(key); return value == null ? null : value.toString(); }
}
