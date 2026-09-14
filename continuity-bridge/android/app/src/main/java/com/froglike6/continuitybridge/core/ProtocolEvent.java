package com.froglike6.continuitybridge;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final Map<String, Object> wirePayload;

    ProtocolEvent(String eventId, String deviceId, String role, String epoch, long sequence,
                  String kind, long createdAtMs, Long expiresAtMs, Map<String, ?> payload) {
        this.eventId = eventId;
        this.deviceId = deviceId;
        this.role = role;
        this.epoch = epoch;
        this.sequence = sequence;
        this.kind = kind;
        this.createdAtMs = createdAtMs;
        this.expiresAtMs = expiresAtMs;
        Map<String, Object> wire = new LinkedHashMap<>();
        Map<String, String> strings = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : payload.entrySet()) {
            wire.put(entry.getKey(), immutable(entry.getValue()));
            if (entry.getValue() instanceof String) strings.put(entry.getKey(), (String) entry.getValue());
        }
        this.wirePayload = Collections.unmodifiableMap(wire);
        this.payload = Collections.unmodifiableMap(strings);
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
    public Map<String, Object> wirePayload() { return wirePayload; }
    public int payloadBytes() {
        return MiniJson.encode(wirePayload).getBytes(StandardCharsets.UTF_8).length;
    }

    private static Object immutable(Object value) {
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("invalid_payload_key");
                copy.put((String) entry.getKey(), immutable(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) copy.add(immutable(item));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
