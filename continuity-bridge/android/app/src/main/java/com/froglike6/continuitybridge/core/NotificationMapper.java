package com.froglike6.continuitybridge;

final class NotificationMapper {
    private NotificationMapper() { }
    static NotificationFields map(String key, String packageName, String appLabel, String title,
                                  String text, String bigText, long postTime) {
        return map(key, packageName, appLabel, title, text, bigText, postTime, null, null, null, null, null);
    }
    static NotificationFields map(String key, String packageName, String appLabel, String title,
                                  String text, String bigText, long postTime, String iconPngBase64,
                                  NotificationProgress progress, Boolean isOngoing, Boolean isRedacted, String category) {
        String body = NotificationContent.hasText(bigText) ? bigText : text;
        String icon = iconPngBase64 != null && Utf8.size(iconPngBase64) <= 16_384 ? iconPngBase64 : null;
        return new NotificationFields(Utf8.truncate(key, 4096), Utf8.truncate(packageName, 255),
                Utf8.truncate(appLabel, 4096), Utf8.truncate(title, 8192), Utf8.truncate(body, 65536), Math.max(0, postTime),
                icon, progress, isOngoing, isRedacted, category == null ? null : Utf8.truncate(category, 128));
    }
    static boolean excludeForeground(String packageName, String channelId, int id,
                                     String ownPackage, String ownChannel, int ownId) {
        return ownPackage.equals(packageName) && ownChannel.equals(channelId) && ownId == id;
    }
    static boolean excludeContentlessGroupSummary(boolean groupSummary, NotificationFields fields) {
        return groupSummary && !NotificationContent.hasText(fields.title()) && !NotificationContent.hasText(fields.body())
                && fields.getProgress() == null && !Boolean.TRUE.equals(fields.getIsRedacted());
    }
}
