package com.froglike6.continuitybridge;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

final class ConfigStore {
    static final String PREFERENCES = "bridge_configuration";
    static final String RUNTIME_STATUS = "runtime_status";
    private final SharedPreferences values;
    ConfigStore(Context context) { values = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE); }

    String endpoint() { return values.getString("endpoint", "https://10.0.2.2:8443"); }
    String pin() { return values.getString("leaf_pin", ""); }
    boolean systemTrust() { return values.getBoolean("system_trust", false); }
    String deviceId() { return stable("device_id", "android-"); }
    String epoch() { return stable("origin_epoch", "epoch-"); }
    ConnectionStatus status() {
        try { return ConnectionStatus.valueOf(values.getString(RUNTIME_STATUS, ConnectionStatus.STOPPED.name())); }
        catch (IllegalArgumentException error) { return ConnectionStatus.DISCONNECTED; }
    }
    void status(ConnectionStatus status) { values.edit().putString(RUNTIME_STATUS, status.name()).apply(); }
    void observe(SharedPreferences.OnSharedPreferenceChangeListener listener) { values.registerOnSharedPreferenceChangeListener(listener); }
    void stopObserving(SharedPreferences.OnSharedPreferenceChangeListener listener) { values.unregisterOnSharedPreferenceChangeListener(listener); }
    void clipboardCapability(String value) { values.edit().putString("clipboard_capability", value).apply(); }
    String clipboardCapability() { return values.getString("clipboard_capability", "확인되지 않음"); }
    void listenerState(NotificationListenerState state) { values.edit().putString("notification_listener", state.name()).apply(); }
    NotificationListenerState listenerState() {
        try { return NotificationListenerState.valueOf(values.getString("notification_listener", NotificationListenerState.DISCONNECTED.name())); }
        catch (IllegalArgumentException error) { return NotificationListenerState.DISCONNECTED; }
    }
    void notificationDeliveryStatus(String value) { values.edit().putString("notification_delivery", value).apply(); }
    String notificationDeliveryStatus() { return values.getString("notification_delivery", "대기 중"); }

    void save(String endpoint, String pin, boolean systemTrust) {
        ConfigValidator.httpsUrl(endpoint);
        String storedPin = systemTrust ? "" : ConfigValidator.pin(pin);
        values.edit().putString("endpoint", endpoint).putString("leaf_pin", storedPin)
                .putBoolean("system_trust", systemTrust).apply();
    }

    private String stable(String key, String prefix) {
        String existing = values.getString(key, null);
        if (existing != null) return existing;
        String created = prefix + UUID.randomUUID().toString(); values.edit().putString(key, created).commit(); return created;
    }
}
