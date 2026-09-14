package com.froglike6.continuitybridge;

import android.content.Context;
import android.content.SharedPreferences;

final class NotificationPreferences {
    private final SharedPreferences values;

    NotificationPreferences(Context context) {
        values = context.getSharedPreferences("notification_senders", Context.MODE_PRIVATE);
    }

    boolean allows(String packageName) { return values.getBoolean(packageName, true); }

    boolean setAllowed(String packageName, boolean allowed) {
        return values.edit().putBoolean(packageName, allowed).commit();
    }
}
