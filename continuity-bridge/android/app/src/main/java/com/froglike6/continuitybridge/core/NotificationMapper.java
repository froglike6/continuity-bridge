package com.froglike6.continuitybridge;

final class NotificationMapper {
    private NotificationMapper() { }
    static NotificationFields map(String key, String packageName, String appLabel, String title,
                                  String text, String bigText, long postTime) {
        String body = bigText == null ? text : bigText;
        return new NotificationFields(Utf8.truncate(key, 4096), Utf8.truncate(packageName, 255),
                Utf8.truncate(appLabel, 4096), Utf8.truncate(title, 8192), Utf8.truncate(body, 65536), Math.max(0, postTime));
    }
    static boolean excludeForeground(String packageName, String channelId, int id,
                                     String ownPackage, String ownChannel, int ownId) {
        return ownPackage.equals(packageName) && ownChannel.equals(channelId) && ownId == id;
    }
}
