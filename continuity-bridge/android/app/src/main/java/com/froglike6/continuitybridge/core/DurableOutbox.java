package com.froglike6.continuitybridge;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class DurableOutbox {
    private final BridgeStateStore store;
    private final EventIds ids;
    private final ObservationWindow observations;

    DurableOutbox(BridgeStateStore store, EventIds ids, ObservationWindow observations) {
        this.store = store; this.ids = ids; this.observations = observations;
    }

    void observeUnavailableCallback(String observationIdentity) {
        String pending = RemoteApplyTracker.pendingEventId();
        if (pending == null || !RemoteApplyTracker.bindObservation(pending, observationIdentity)) return;
        synchronized (store) {
            try {
                BridgeState state = store.load();
                if (pending.equals(state.remoteApplyId()) && state.remoteApplyObservationIdentity() == null)
                    store.save(state.markRemoteApply(pending, observationIdentity));
            } catch (IOException | RuntimeException ignored) { }
        }
    }

    CaptureResult captureClipboard(String text, String marker, String observationIdentity, long nowMs) {
        if (text == null || Utf8.size(text) > 1_048_576) return CaptureResult.INVALID;
        synchronized (store) {
            try {
                BridgeState state = store.load();
                boolean knownMarker = marker != null && (marker.equals(state.remoteApplyId())
                        || state.appliedIds().contains(marker) || RemoteApplyTracker.matches(marker));
                String boundObservation = marker != null && marker.equals(state.remoteApplyId())
                        ? state.remoteApplyObservationIdentity() : null;
                if (boundObservation == null && marker != null)
                    boundObservation = RemoteApplyTracker.observationIdentity(marker);
                boolean distinctStableObservation = boundObservation != null
                        && boundObservation.startsWith("timestamp:") && observationIdentity.startsWith("timestamp:")
                        && !boundObservation.equals(observationIdentity);
                boolean remoteMarker = knownMarker && !distinctStableObservation;
                boolean durableObservation = state.remoteApplyObservationIdentity() != null
                        && state.remoteApplyObservationIdentity().equals(observationIdentity);
                boolean remoteObservation = marker == null && (durableObservation
                        || RemoteApplyTracker.matchesObservation(observationIdentity));
                if (remoteMarker) {
                    boolean bound = RemoteApplyTracker.bindObservation(marker, observationIdentity);
                    if (bound && marker.equals(state.remoteApplyId()) && state.remoteApplyObservationIdentity() == null) {
                        state = state.markRemoteApply(marker, observationIdentity);
                        store.save(state);
                    }
                    observations.accept(observationIdentity, nowMs);
                    return CaptureResult.REMOTE_SKIPPED;
                }
                if (remoteObservation) {
                    observations.accept(observationIdentity, nowMs);
                    return CaptureResult.REMOTE_SKIPPED;
                }
                if (!observations.accept(observationIdentity, nowMs)) return CaptureResult.DUPLICATE;
                String pending = RemoteApplyTracker.pendingEventId();
                if (pending != null) RemoteApplyTracker.clear(pending);
                if (state.remoteApplyId() != null) state = state.clearRemoteApply(state.remoteApplyId());
                ProtocolEvent event = state.clipboardEvent(ids.next(), nowMs, text);
                store.save(state.enqueue(event, nowMs)); return CaptureResult.ENQUEUED;
            } catch (IOException | RuntimeException error) { return CaptureResult.UNAVAILABLE; }
        }
    }

    boolean enqueueNotification(NotificationFields fields, long nowMs) {
        synchronized (store) {
            try {
                BridgeState state = store.load(); Map<String, String> payload = new LinkedHashMap<>();
                payload.put("notificationKey", fields.notificationKey()); payload.put("packageName", fields.packageName());
                payload.put("appLabel", fields.appLabel()); payload.put("title", fields.title()); payload.put("body", fields.body());
                ProtocolEvent event = new ProtocolEvent(ids.next(), state.deviceId(), "android", state.epoch(), state.nextSequence(),
                        "android.notification", fields.postTime(), Math.min(9_007_199_254_740_991L, nowMs + 900_000), payload);
                store.save(state.enqueue(event, nowMs)); return true;
            } catch (IOException | RuntimeException error) { return false; }
        }
    }
}
