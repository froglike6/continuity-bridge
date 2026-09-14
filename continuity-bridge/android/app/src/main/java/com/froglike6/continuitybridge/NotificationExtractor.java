package com.froglike6.continuitybridge;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NotificationExtractor {
    private final Context context;
    private final Map<String, ApplicationIdentity> identities = new LinkedHashMap<>();

    NotificationExtractor(Context context) { this.context = context.getApplicationContext(); }

    NotificationFields extract(StatusBarNotification source) {
        Notification notification = source.getNotification();
        Bundle extras = notification.extras;
        ApplicationIdentity identity = identity(source.getPackageName());
        String title = value(extras, Notification.EXTRA_TITLE);
        if (!NotificationContent.hasText(title)) title = value(extras, Notification.EXTRA_TITLE_BIG);
        if (!NotificationContent.hasText(title)) title = value(extras, Notification.EXTRA_CONVERSATION_TITLE);
        String normal = value(extras, Notification.EXTRA_TEXT);
        String expanded = value(extras, Notification.EXTRA_BIG_TEXT);
        String messages = messages(extras);
        String marker = redactionMarker(context.getResources());
        boolean redacted = NotificationContent.isRedacted(marker, normal, expanded, messages);
        String conversationTitle = value(extras, Notification.EXTRA_CONVERSATION_TITLE);
        if (!redacted && messages != null && NotificationContent.hasText(conversationTitle)) title = conversationTitle;
        String body = redacted ? marker : NotificationContent.body(normal, expanded, messages, lines(extras));
        boolean ongoing = source.isOngoing();
        NotificationProgress progress = NotificationContent.progress(integer(extras, Notification.EXTRA_PROGRESS),
                integer(extras, Notification.EXTRA_PROGRESS_MAX), extras != null && extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
                ongoing, title, body);
        return NotificationMapper.map(source.getKey(), source.getPackageName(), identity.label, title, body, null,
                source.getPostTime(), identity.icon, progress, ongoing, redacted, notification.category);
    }

    static String redactionMarker(Resources resources) {
        int identifier = resources.getIdentifier("redacted_notification_message", "string", "android");
        if (identifier == 0) return null;
        try { return resources.getString(identifier); }
        catch (Resources.NotFoundException unavailable) { return null; }
    }

    private ApplicationIdentity identity(String packageName) {
        PackageManager manager = context.getPackageManager();
        try {
            PackageInfo installed = manager.getPackageInfo(packageName, 0);
            ApplicationIdentity cached = identities.get(packageName);
            if (cached != null && cached.updatedAt == installed.lastUpdateTime) return cached;
            ApplicationInfo info = installed.applicationInfo;
            String label = info == null ? packageName : text(manager.getApplicationLabel(info));
            String icon = null;
            try { if (info != null) icon = NotificationAppIcon.encode(manager.getApplicationIcon(info)); }
            catch (Resources.NotFoundException | IllegalArgumentException unavailable) { }
            ApplicationIdentity current = new ApplicationIdentity(NotificationContent.hasText(label) ? label : packageName,
                    icon, installed.lastUpdateTime);
            if (identities.size() >= 48) identities.remove(identities.keySet().iterator().next());
            identities.put(packageName, current);
            return current;
        } catch (PackageManager.NameNotFoundException | SecurityException unavailable) {
            return new ApplicationIdentity(packageName, null, -1);
        }
    }

    private static String messages(Bundle extras) {
        Parcelable[] bundles = extras == null ? null : extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (bundles == null) return null;
        List<String> lines = new ArrayList<>();
        for (Notification.MessagingStyle.Message message : Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)) {
            String body = text(message.getText());
            if (!NotificationContent.hasText(body)) continue;
            Person sender = message.getSenderPerson();
            String name = sender == null ? value(extras, Notification.EXTRA_SELF_DISPLAY_NAME) : text(sender.getName());
            lines.add(NotificationContent.hasText(name) ? name + ": " + body : body);
        }
        return NotificationContent.joinLines(lines.toArray(new String[0]));
    }

    private static String[] lines(Bundle extras) {
        CharSequence[] values = extras == null ? null : extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (values == null) return null;
        String[] result = new String[values.length];
        for (int i = 0; i < values.length; i++) result[i] = text(values[i]);
        return result;
    }

    private static String value(Bundle extras, String key) { return extras == null ? null : text(extras.getCharSequence(key)); }
    private static String text(CharSequence value) { return value == null ? null : value.toString(); }
    private static Integer integer(Bundle extras, String key) { return extras == null || !extras.containsKey(key) ? null : extras.getInt(key, -1); }

    private static final class ApplicationIdentity {
        final String label, icon;
        final long updatedAt;
        ApplicationIdentity(String label, String icon, long updatedAt) { this.label = label; this.icon = icon; this.updatedAt = updatedAt; }
    }
}
