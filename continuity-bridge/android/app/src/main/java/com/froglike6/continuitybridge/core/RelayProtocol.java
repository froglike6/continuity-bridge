package com.froglike6.continuitybridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RelayProtocol {
    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    private static final int RESPONSE_LIMIT = 1_200_000;
    private RelayProtocol() { }

    public static final class Fetch {
        private final String nextCursor;
        private final String serverEpoch;
        private final List<Entry> entries;
        Fetch(String nextCursor, String serverEpoch, List<Entry> entries) { this.nextCursor = nextCursor; this.serverEpoch = serverEpoch; this.entries = entries; }
        public String nextCursor() { return nextCursor; }
        public String serverEpoch() { return serverEpoch; }
        public List<Entry> entries() { return entries; }
    }

    public static final class Entry {
        private final String cursor;
        private final ProtocolEvent event;
        Entry(String cursor, ProtocolEvent event) { this.cursor = cursor; this.event = event; }
        public String cursor() { return cursor; }
        public ProtocolEvent event() { return event; }
    }

    @SuppressWarnings("unchecked")
    public static Fetch fetch(String body, String requestedAfter) {
        Map<String, Object> object = object(body);
        requireVersion(object); String serverEpoch = serverEpoch(object);
        String after = cursor(object.get("after")); String next = cursor(object.get("nextCursor"));
        if (!requestedAfter.equals(after) || compare(next, after) < 0) throw new IllegalArgumentException("invalid_cursor_replay");
        Object rawEvents = object.get("events"); if (!(rawEvents instanceof List)) throw new IllegalArgumentException("invalid_events");
        List<Object> values = (List<Object>) rawEvents; if (values.size() > 100) throw new IllegalArgumentException("too_many_events");
        List<Entry> entries = new ArrayList<>(); String previous = after;
        for (Object raw : values) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("invalid_event_entry");
            Map<String, Object> entry = (Map<String, Object>) raw;
            String entryCursor = cursor(entry.get("cursor"));
            if (compare(entryCursor, previous) <= 0 || compare(entryCursor, next) > 0) throw new IllegalArgumentException("invalid_event_cursor");
            Object rawEvent = entry.get("event"); if (!(rawEvent instanceof Map)) throw new IllegalArgumentException("invalid_event_entry");
            ProtocolEvent event = EventCodec.decode(MiniJson.encode(rawEvent));
            if (!"macos".equals(event.role()) || !"clipboard.text".equals(event.kind())) throw new IllegalArgumentException("invalid_recipient_event");
            entries.add(new Entry(entryCursor, event)); previous = entryCursor;
        }
        return new Fetch(next, serverEpoch, entries);
    }

    public static String published(String body, String expectedEventId) {
        Map<String, Object> object = object(body);
        if (!Boolean.TRUE.equals(object.get("accepted")) || !expectedEventId.equals(requiredString(object, "eventId", 128, false))) {
            throw new IllegalArgumentException("invalid_publish_response");
        }
        cursor(object.get("cursor")); String serverEpoch = serverEpoch(object);
        if (!(object.get("idempotent") instanceof Boolean)) throw new IllegalArgumentException("invalid_publish_response");
        return serverEpoch;
    }

    @SuppressWarnings("unchecked")
    public static void acknowledged(String body, List<String> expectedIds) {
        Map<String, Object> object = object(body); Set<String> returned = new HashSet<>();
        for (String key : new String[] {"acked", "alreadyAbsent"}) {
            Object raw = object.get(key); if (!(raw instanceof List)) throw new IllegalArgumentException("invalid_ack_response");
            List<Object> values = (List<Object>) raw; if (values.size() > 100) throw new IllegalArgumentException("invalid_ack_response");
            for (Object value : values) {
                if (!(value instanceof String) || ((String) value).isEmpty() || ((String) value).getBytes(StandardCharsets.UTF_8).length > 128 || !returned.add((String) value)) {
                    throw new IllegalArgumentException("invalid_ack_response");
                }
            }
        }
        if (!returned.equals(new HashSet<>(expectedIds))) throw new IllegalArgumentException("ack_identity_mismatch");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > RESPONSE_LIMIT) throw new IllegalArgumentException("invalid_response_size");
        Object value = MiniJson.parse(body); if (!(value instanceof Map)) throw new IllegalArgumentException("invalid_response");
        return (Map<String, Object>) value;
    }
    private static void requireVersion(Map<String, Object> object) { if (!Long.valueOf(1).equals(object.get("protocolVersion"))) throw new IllegalArgumentException("unsupported_response_version"); }
    private static String serverEpoch(Map<String, Object> object) {
        String value = requiredString(object, "serverEpoch", 32, false);
        if (!value.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("invalid_server_epoch"); return value;
    }
    private static String requiredString(Map<String, Object> object, String key, int limit, boolean empty) {
        Object value = object.get(key); if (!(value instanceof String)) throw new IllegalArgumentException("invalid_" + key);
        String text = (String) value; int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if ((!empty && text.isEmpty()) || bytes > limit) throw new IllegalArgumentException("invalid_" + key); return text;
    }
    private static String cursor(Object value) {
        if (!(value instanceof String) || !((String) value).matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("invalid_cursor");
        try { long parsed = Long.parseLong((String) value); if (parsed > MAX_SAFE) throw new IllegalArgumentException("invalid_cursor"); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("invalid_cursor", error); }
        return (String) value;
    }
    private static int compare(String left, String right) { return Long.compare(Long.parseLong(left), Long.parseLong(right)); }
}
