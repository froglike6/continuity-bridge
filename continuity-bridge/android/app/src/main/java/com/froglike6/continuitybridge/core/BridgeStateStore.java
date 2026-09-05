package com.froglike6.continuitybridge;

import java.io.IOException;

public interface BridgeStateStore {
    BridgeState load() throws IOException;
    void save(BridgeState state) throws IOException;
}
