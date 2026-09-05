package com.froglike6.continuitybridge;

import java.io.IOException;

public interface BridgeStateStore {
    @FunctionalInterface
    interface Mutation {
        BridgeState apply(BridgeState state) throws IOException;
    }

    BridgeState load() throws IOException;
    void save(BridgeState state) throws IOException;

    default BridgeState update(Mutation mutation) throws IOException {
        synchronized (this) {
            BridgeState current = load();
            BridgeState next = mutation.apply(current);
            if (next != current) save(next);
            return next;
        }
    }
}
