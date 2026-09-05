package com.froglike6.continuitybridge;

import android.content.Context;
import java.io.File;
import java.io.IOException;

final class BridgeRepository {
    private static BridgeRepository instance;
    private final FileBridgeStateStore store;
    private final DurableOutbox outbox;

    private BridgeRepository(Context context) throws IOException {
        ConfigStore config = new ConfigStore(context);
        store = FileBridgeStateStore.create(new File(context.getFilesDir(), "bridge-state-v1").toPath(), new AndroidKeystoreStateCipher(),
                config.deviceId(), config.epoch(), 101, 4_096);
        outbox = new DurableOutbox(store, new UuidIds(), new ObservationWindow(1_000, 32));
    }

    static synchronized BridgeRepository get(Context context) throws IOException {
        if (instance == null) instance = new BridgeRepository(context.getApplicationContext());
        return instance;
    }
    FileBridgeStateStore store() { return store; }
    DurableOutbox outbox() { return outbox; }
}
