package com.froglike6.continuitybridge;

final class NotificationFields {
    private final String key, packageName, appLabel, title, body; private final long postTime;
    NotificationFields(String key, String packageName, String appLabel, String title, String body, long postTime) {
        this.key = key; this.packageName = packageName; this.appLabel = appLabel; this.title = title; this.body = body; this.postTime = postTime;
    }
    String notificationKey() { return key; }
    String packageName() { return packageName; }
    String appLabel() { return appLabel; }
    String title() { return title; }
    String body() { return body; }
    long postTime() { return postTime; }
}
