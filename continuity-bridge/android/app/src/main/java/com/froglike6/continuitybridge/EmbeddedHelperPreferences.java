package com.froglike6.continuitybridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

final class EmbeddedHelperPreferences {
    private final SharedPreferences values;
    EmbeddedHelperPreferences(Context context) {
        values = context.getSharedPreferences("embedded_clipboard_helper", Context.MODE_PRIVATE);
    }
    boolean paired() { return values.getBoolean("paired", false); }
    boolean automatic() { return Build.VERSION.SDK_INT >= 33 && values.getBoolean("automatic_recovery", false); }
    boolean bridgeRequested() { return values.getBoolean("bridge_requested", false); }
    void paired(boolean value) { save("paired", value); }
    void automatic(boolean value) { save("automatic_recovery", value && Build.VERSION.SDK_INT >= 33); }
    void bridgeRequested(boolean value) { save("bridge_requested", value); }
    private void save(String name, boolean value) {
        if (!values.edit().putBoolean(name, value).commit()) throw new IllegalStateException("helper_settings_save_failed");
    }
}
