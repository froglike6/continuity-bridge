package com.froglike6.continuitybridge;

public final class NotificationDeliveryPolicy {
    private static final int IMPORTANCE_LOW = 2;

    private NotificationDeliveryPolicy() { }

    public static boolean shouldMirror(String category, boolean ongoing, boolean foregroundService,
                                       int importance, boolean progress, boolean continuingProgress) {
        if ("sys".equals(category) || "service".equals(category)
                || "status".equals(category) || "transport".equals(category)) return false;
        if (progress || continuingProgress) return true;
        return !ongoing && !foregroundService && (importance < 0 || importance > IMPORTANCE_LOW);
    }
}
