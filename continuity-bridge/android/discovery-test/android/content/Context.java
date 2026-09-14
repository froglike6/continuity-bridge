package android.content;

import android.net.nsd.NsdManager;

public final class Context {
    private final NsdManager manager;

    public Context(NsdManager manager) { this.manager = manager; }

    public <T> T getSystemService(Class<T> serviceClass) {
        return serviceClass.cast(manager);
    }
}
