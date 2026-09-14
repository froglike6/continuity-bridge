package android.content;

import android.net.ConnectivityManager;
import java.util.HashMap;
import java.util.Map;

public final class Context {
    public static final int MODE_PRIVATE = 0;
    public final ConnectivityManager connectivity = new ConnectivityManager();
    public int foregroundStarts;
    private final Map<String, SharedPreferences> preferences = new HashMap<>();
    public Context getApplicationContext() { return this; }
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return preferences.computeIfAbsent(name, key -> new SharedPreferences());
    }
    public <T> T getSystemService(Class<T> service) { return service.cast(connectivity); }
    public void startForegroundService(Intent intent) { foregroundStarts++; }
}
