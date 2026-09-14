package com.froglike6.continuitybridge;

import java.io.IOException;

final class ClipboardApplyTransaction {
    private final BridgeStateStore store;
    private final ClipboardSurface surface;
    ClipboardApplyTransaction(BridgeStateStore store, ClipboardSurface surface) { this.store = store; this.surface = surface; }
    boolean apply(ProtocolEvent event) {
        final ClipboardContent content;
        try { content = ClipboardContent.fromEvent(event); }
        catch (IllegalArgumentException invalid) { return false; }
        RemoteApplyTracker.beginContent(event.eventId(), content);
        try {
            store.update(new BridgeStateStore.Mutation() {
                @Override public BridgeState apply(BridgeState state) {
                    return state.markRemoteApply(event.eventId(), event.eventId().equals(state.remoteApplyId())
                            ? state.remoteApplyObservationIdentity() : null);
                }
            });
            if (!surface.setContent(event.eventId(), content) || !surface.confirmContent(event.eventId(), content)) return false;
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
