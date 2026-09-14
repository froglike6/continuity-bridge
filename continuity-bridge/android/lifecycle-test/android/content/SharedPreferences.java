package android.content;

import java.util.HashMap;
import java.util.Map;

public final class SharedPreferences {
    private final Map<String, Boolean> values = new HashMap<>();
    public boolean getBoolean(String name, boolean fallback) { return values.getOrDefault(name, fallback); }
    public Editor edit() { return new Editor(); }
    public final class Editor {
        private final Map<String, Boolean> changes = new HashMap<>();
        public Editor putBoolean(String name, boolean value) { changes.put(name, value); return this; }
        public boolean commit() { values.putAll(changes); return true; }
    }
}
