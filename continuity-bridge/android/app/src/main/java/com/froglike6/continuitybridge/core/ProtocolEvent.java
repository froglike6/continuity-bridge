package com.froglike6.continuitybridge;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProtocolEvent {
    private final String eventId;
    private final String deviceId;
    private final String role;
    private final String epoch;
    private final long sequence;
    private final String kind;
    private final long createdAtMs;
    private final Long expiresAtMs;
    private final Map<String, String> payload;

    ProtocolEvent(String eventId, String deviceId, String role, String epoch, long sequence,
                  String kind, long createdAtMs, Long expiresAtMs, Map<String, String> payload) {
        this.eventId = eventId;
        this.deviceId = deviceId;
        this.role = role;
        this.epoch = epoch;
        this.sequence = sequence;
        this.kind = kind;
        this.createdAtMs = createdAtMs;
        this.expiresAtMs = expiresAtMs;
        this.payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public String eventId() { return eventId; }
    public String deviceId() { return deviceId; }
    public String role() { return role; }
    public String epoch() { return epoch; }
    public long sequence() { return sequence; }
    public String kind() { return kind; }
    public long createdAtMs() { return createdAtMs; }
    public Long expiresAtMs() { return expiresAtMs; }
    public Map<String, String> payload() { return payload; }
    public int payloadBytes() {
        int total = 0;
        for (String value : payload.values()) total += value.getBytes(StandardCharsets.UTF_8).length;
        return total;
    }
}
