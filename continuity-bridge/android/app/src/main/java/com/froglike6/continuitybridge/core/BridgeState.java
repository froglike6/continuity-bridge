package com.froglike6.continuitybridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BridgeState {
    public static final int MAX_REPLAY_ORIGIN_KEYS = 64;
    public enum Delivery { NEW, DUPLICATE, STALE, CONFLICT }
    private final String deviceId;
    private final String epoch;
    private final long nextSequence;
    private final String cursor;
    private final String serverEpoch;
    private final int outboxLimit;
    private final int appliedLimit;
    private final List<ProtocolEvent> outbox;
    private final LinkedHashSet<String> applied;
    private final Map<String, Long> highWater;
    private final Map<String, String> fingerprints;
    private final LinkedHashSet<String> pendingAcks;
    private final String remoteApplyId;
    private final String remoteApplyObservationIdentity;

    private BridgeState(String deviceId, String epoch, long nextSequence, String cursor, String serverEpoch, int outboxLimit,
                        int appliedLimit, List<ProtocolEvent> outbox, LinkedHashSet<String> applied,
                        Map<String, Long> highWater, Map<String, String> fingerprints, LinkedHashSet<String> pendingAcks,
                        String remoteApplyId, String remoteApplyObservationIdentity) {
        this.deviceId = deviceId; this.epoch = epoch; this.nextSequence = nextSequence; this.cursor = cursor; this.serverEpoch = serverEpoch;
        this.outboxLimit = outboxLimit; this.appliedLimit = appliedLimit;
        this.outbox = Collections.unmodifiableList(new ArrayList<>(outbox));
        this.applied = new LinkedHashSet<>(applied); this.highWater = new LinkedHashMap<>(highWater);
        this.fingerprints = new LinkedHashMap<>(fingerprints); this.pendingAcks = new LinkedHashSet<>(pendingAcks);
        this.remoteApplyId = remoteApplyId; this.remoteApplyObservationIdentity = remoteApplyObservationIdentity;
    }

    public static BridgeState fresh(String deviceId, String epoch, int outboxLimit, int appliedLimit) {
        if (outboxLimit < 1 || appliedLimit < 1) throw new IllegalArgumentException("invalid_limits");
        return new BridgeState(deviceId, epoch, 1, "0", "", outboxLimit, appliedLimit,
                Collections.<ProtocolEvent>emptyList(), new LinkedHashSet<String>(),
                new LinkedHashMap<String, Long>(), new LinkedHashMap<String, String>(), new LinkedHashSet<String>(), null, null);
    }

    static BridgeState restore(String deviceId, String epoch, long nextSequence, String cursor, String serverEpoch, int outboxLimit,
                               int appliedLimit, List<ProtocolEvent> outbox, LinkedHashSet<String> applied,
                               Map<String, Long> highWater, Map<String, String> fingerprints, LinkedHashSet<String> pendingAcks,
                               String remoteApplyId, String remoteApplyObservationIdentity) {
        if (nextSequence < 1 || outbox.size() > outboxLimit || applied.size() > appliedLimit || highWater.size() > MAX_REPLAY_ORIGIN_KEYS
                || fingerprints.size() > appliedLimit || pendingAcks.size() > appliedLimit) throw new IllegalArgumentException("invalid_state");
        if (remoteApplyObservationIdentity != null && remoteApplyId == null) throw new IllegalArgumentException("orphan_remote_observation");
        return new BridgeState(deviceId, epoch, nextSequence, cursor, serverEpoch, outboxLimit, appliedLimit,
                outbox, applied, highWater, fingerprints, pendingAcks, remoteApplyId, remoteApplyObservationIdentity);
    }

    public BridgeState enqueue(ProtocolEvent event) { return enqueue(event, event.createdAtMs()); }

    public BridgeState enqueue(ProtocolEvent event, long nowMs) {
        if (nowMs < 0) throw new IllegalArgumentException("invalid_now");
        EventCodec.encode(event);
        for (ProtocolEvent existing : outbox) {
            if (existing.eventId().equals(event.eventId())) {
                if (EventCodec.encode(existing).equals(EventCodec.encode(event))) return this;
                throw new IllegalStateException("conflicting_retry");
            }
        }
        if (!"android".equals(event.role()) || !deviceId.equals(event.deviceId()) || !epoch.equals(event.epoch()) || event.sequence() != nextSequence) {
            throw new IllegalArgumentException("outbound_identity_mismatch");
        }
        List<ProtocolEvent> next = new ArrayList<>(outbox);
        if (isClipboard(event)) {
            for (int index = next.size() - 1; index >= 0; index--) if (isClipboard(next.get(index))) next.remove(index);
        }
        next.add(event);
        for (int index = next.size() - 1; index >= 0; index--) {
            ProtocolEvent candidate = next.get(index);
            if ("android.notification".equals(candidate.kind()) && candidate.expiresAtMs() != null && candidate.expiresAtMs() <= nowMs) next.remove(index);
        }
        while (notificationCount(next) > 100 || notificationBytes(next) > 524_288) {
            int oldest = oldestNotification(next); if (oldest < 0) break; next.remove(oldest);
        }
        if (next.size() > outboxLimit) throw new IllegalStateException("outbox_full");
        return copy(nextSequence + 1, cursor, next, applied, highWater, fingerprints, pendingAcks,
                remoteApplyId, remoteApplyObservationIdentity);
    }

    public ProtocolEvent clipboardEvent(String eventId, long createdAtMs, String text) {
        Map<String, String> payload = new LinkedHashMap<>(); payload.put("text", text);
        return new ProtocolEvent(eventId, deviceId, "android", epoch, nextSequence, "clipboard.text", createdAtMs, null, payload);
    }

    ProtocolEvent clipboardEvent(String eventId, long createdAtMs, ClipboardContent content) {
        return new ProtocolEvent(eventId, deviceId, "android", epoch, nextSequence, content.kind(), createdAtMs, null, content.payload());
    }

    private static boolean isClipboard(ProtocolEvent event) {
        return "clipboard.text".equals(event.kind()) || "clipboard.image".equals(event.kind());
    }

    private static int notificationCount(List<ProtocolEvent> events) {
        int count = 0; for (ProtocolEvent event : events) if ("android.notification".equals(event.kind())) count++; return count;
    }
    private static int notificationBytes(List<ProtocolEvent> events) {
        int bytes = 0; for (ProtocolEvent event : events) if ("android.notification".equals(event.kind())) bytes += EventCodec.encode(event).getBytes(java.nio.charset.StandardCharsets.UTF_8).length; return bytes;
    }
    private static int oldestNotification(List<ProtocolEvent> events) {
        int oldest = -1;
        for (int index = 0; index < events.size(); index++) {
            if (!"android.notification".equals(events.get(index).kind())) continue;
            if (oldest < 0 || events.get(index).sequence() < events.get(oldest).sequence()) oldest = index;
        }
        return oldest;
    }

    public BridgeState acknowledge(Set<String> ids) {
        List<ProtocolEvent> next = new ArrayList<>();
        for (ProtocolEvent event : outbox) if (!ids.contains(event.eventId())) next.add(event);
        return copy(nextSequence, cursor, next, applied, highWater, fingerprints, pendingAcks,
                remoteApplyId, remoteApplyObservationIdentity);
    }

    public Delivery classify(ProtocolEvent event) {
        if (applied.contains(event.eventId())) return Delivery.DUPLICATE;
        String origin = event.deviceId() + "\u0000" + event.epoch();
        Long high = highWater.get(origin);
        if (high == null && highWater.size() >= MAX_REPLAY_ORIGIN_KEYS) return Delivery.CONFLICT;
        if (high == null || event.sequence() > high) return Delivery.NEW;
        String known = fingerprints.get(origin + "\u0000" + event.sequence());
        return event.eventId().equals(known) ? Delivery.DUPLICATE : (event.sequence() < high ? Delivery.STALE : Delivery.CONFLICT);
    }

    public BridgeState applied(ProtocolEvent event, String nextCursor) {
        String origin = event.deviceId() + "\u0000" + event.epoch();
        if (!highWater.containsKey(origin) && highWater.size() >= MAX_REPLAY_ORIGIN_KEYS) {
            throw new IllegalStateException("replay_origin_full");
        }
        LinkedHashSet<String> pending = new LinkedHashSet<>(pendingAcks); pending.add(event.eventId());
        LinkedHashSet<String> ids = new LinkedHashSet<>(applied); ids.remove(event.eventId()); ids.add(event.eventId());
        while (ids.size() > appliedLimit) ids.remove(oldestUnprotected(ids, pending, remoteApplyId));
        Map<String, Long> waters = new LinkedHashMap<>(highWater);
        Map<String, String> prints = new LinkedHashMap<>(fingerprints);
        Long high = waters.get(origin);
        if (high == null || event.sequence() > high) waters.put(origin, event.sequence());
        String fingerprint = origin + "\u0000" + event.sequence(); prints.remove(fingerprint); prints.put(fingerprint, event.eventId());
        while (prints.size() > appliedLimit) prints.remove(oldestFingerprint(prints, pending, remoteApplyId));
        String observation = event.eventId().equals(remoteApplyId) ? remoteApplyObservationIdentity : null;
        return copy(nextSequence, nextCursor, outbox, ids, waters, prints, pending, event.eventId(), observation);
    }

    private static String oldestUnprotected(LinkedHashSet<String> ids, Set<String> pending, String marker) {
        for (String id : ids) if (!pending.contains(id) && !id.equals(marker)) return id;
        throw new IllegalStateException("applied_cache_live_full");
    }
    private static String oldestFingerprint(Map<String, String> prints, Set<String> pending, String marker) {
        for (Map.Entry<String, String> entry : prints.entrySet()) {
            if (!pending.contains(entry.getValue()) && !entry.getValue().equals(marker)) return entry.getKey();
        }
        throw new IllegalStateException("fingerprint_cache_live_full");
    }

    public BridgeState clearRemoteApply(String eventId) {
        return eventId.equals(remoteApplyId)
                ? copy(nextSequence, cursor, outbox, applied, highWater, fingerprints, pendingAcks, null, null) : this;
    }

    public BridgeState markRemoteApply(String eventId, String observationIdentity) {
        if (eventId == null || eventId.isEmpty()) throw new IllegalArgumentException("invalid_remote_marker");
        if (observationIdentity != null && observationIdentity.isEmpty()) throw new IllegalArgumentException("invalid_remote_observation");
        return copy(nextSequence, cursor, outbox, applied, highWater, fingerprints, pendingAcks,
                eventId, observationIdentity);
    }

    public BridgeState published(String eventId) { return acknowledge(Collections.singleton(eventId)); }
    public BridgeState cursor(String value) { return copy(nextSequence, value, outbox, applied, highWater, fingerprints,
            pendingAcks, remoteApplyId, remoteApplyObservationIdentity); }
    public BridgeState relay(String epoch, String value) {
        return new BridgeState(deviceId, this.epoch, nextSequence, value, epoch, outboxLimit, appliedLimit,
                outbox, applied, highWater, fingerprints, pendingAcks, remoteApplyId, remoteApplyObservationIdentity);
    }
    public BridgeState pendingAck(String eventId, String nextCursor) {
        LinkedHashSet<String> pending = new LinkedHashSet<>(pendingAcks); pending.add(eventId);
        return copy(nextSequence, nextCursor, outbox, applied, highWater, fingerprints, pending,
                remoteApplyId, remoteApplyObservationIdentity);
    }
    public BridgeState acknowledged(Set<String> ids) {
        LinkedHashSet<String> pending = new LinkedHashSet<>(pendingAcks); pending.removeAll(ids);
        return copy(nextSequence, cursor, outbox, applied, highWater, fingerprints, pending,
                remoteApplyId, remoteApplyObservationIdentity);
    }

    private BridgeState copy(long sequence, String nextCursor, List<ProtocolEvent> queue, LinkedHashSet<String> ids,
                             Map<String, Long> waters, Map<String, String> prints, LinkedHashSet<String> pending,
                             String marker, String observationIdentity) {
        return new BridgeState(deviceId, epoch, sequence, nextCursor, serverEpoch, outboxLimit, appliedLimit,
                queue, ids, waters, prints, pending, marker, observationIdentity);
    }

    public String deviceId() { return deviceId; }
    public String epoch() { return epoch; }
    public long nextSequence() { return nextSequence; }
    public String cursor() { return cursor; }
    public String serverEpoch() { return serverEpoch; }
    public List<ProtocolEvent> outbox() { return outbox; }
    public Set<String> appliedIds() { return Collections.unmodifiableSet(applied); }
    public Map<String, Long> highWater() { return Collections.unmodifiableMap(highWater); }
    public String remoteApplyId() { return remoteApplyId; }
    public String remoteApplyObservationIdentity() { return remoteApplyObservationIdentity; }
    public Set<String> pendingAcks() { return Collections.unmodifiableSet(pendingAcks); }
    int outboxLimit() { return outboxLimit; }
    int appliedLimit() { return appliedLimit; }
    Map<String, String> fingerprints() { return Collections.unmodifiableMap(fingerprints); }
}
