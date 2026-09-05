package com.froglike6.continuitybridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileBridgeStateStore implements BridgeStateStore {
    private final AtomicStateFile file;
    private FileBridgeStateStore(AtomicStateFile file) { this.file = file; }

    public static FileBridgeStateStore create(Path path, StateCipher cipher, String deviceId, String epoch, int outboxLimit, int appliedLimit) throws IOException {
        AtomicStateFile file = new AtomicStateFile(path, cipher);
        if (!Files.exists(path) && !Files.exists(path.resolveSibling(path.getFileName() + ".bak"))) {
            file.save(BridgeState.fresh(deviceId, epoch, outboxLimit, appliedLimit));
        }
        return new FileBridgeStateStore(file);
    }

    @Override public synchronized BridgeState load() throws IOException { return file.load(); }
    @Override public synchronized void save(BridgeState state) throws IOException { file.save(state); }
}
