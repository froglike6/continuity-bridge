package com.froglike6.continuitybridge;

final class NotificationFields {
    private final String key, packageName, appLabel, title, body; private final long postTime;
    private final String iconPngBase64, category;
    private final NotificationProgress progress;
    private final Boolean isOngoing, isRedacted;
    NotificationFields(String key, String packageName, String appLabel, String title, String body, long postTime) {
        this(key, packageName, appLabel, title, body, postTime, null, null, null, null, null);
    }
    NotificationFields(String key, String packageName, String appLabel, String title, String body, long postTime,
                       String iconPngBase64, NotificationProgress progress, Boolean isOngoing, Boolean isRedacted, String category) {
        this.key = key; this.packageName = packageName; this.appLabel = appLabel; this.title = title; this.body = body; this.postTime = postTime;
        this.iconPngBase64 = iconPngBase64; this.progress = progress; this.isOngoing = isOngoing;
        this.isRedacted = isRedacted; this.category = category;
    }
    String notificationKey() { return key; }
    String packageName() { return packageName; }
    String appLabel() { return appLabel; }
    String title() { return title; }
    String body() { return body; }
    long postTime() { return postTime; }
    String getIconPngBase64() { return iconPngBase64; }
    NotificationProgress getProgress() { return progress; }
    Boolean getIsOngoing() { return isOngoing; }
    Boolean getIsRedacted() { return isRedacted; }
    String getCategory() { return category; }
}
