package com.froglike6.continuitybridge;

import java.io.IOException;

final class ClipboardApplyTransaction {
    private final BridgeStateStore store;
    private final ClipboardSurface surface;
    ClipboardApplyTransaction(BridgeStateStore store, ClipboardSurface surface) { this.store = store; this.surface = surface; }
    boolean apply(ProtocolEvent event) {
        if (!"clipboard.text".equals(event.kind())) return false;
        String text = event.payload().get("text");
        if (text == null || Utf8.size(text) > 1_048_576) return false;
        RemoteApplyTracker.begin(event.eventId());
        try {
            if (!surface.set(event.eventId(), text)) return false;
            synchronized (store) {
                store.save(store.load().markRemoteApply(event.eventId(),
                        RemoteApplyTracker.observationIdentity(event.eventId())));
            }
            return true;
        }
        catch (IOException | RuntimeException error) { return false; }
        finally { RemoteApplyTracker.clear(event.eventId()); }
    }
}
