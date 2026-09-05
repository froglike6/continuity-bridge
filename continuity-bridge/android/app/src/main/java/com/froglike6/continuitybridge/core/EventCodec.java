package com.froglike6.continuitybridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EventCodec {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private EventCodec() { }

    public static ProtocolEvent decode(Path path) throws IOException { return decode(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)); }

    @SuppressWarnings("unchecked")
    public static ProtocolEvent decode(String json) {
        if (json.getBytes(StandardCharsets.UTF_8).length > 1_114_112) throw new IllegalArgumentException("event_too_large");
        Object value = MiniJson.parse(json);
        if (!(value instanceof Map)) throw new IllegalArgumentException("invalid_event");
        Map<String, Object> object = (Map<String, Object>) value;
        if (!Long.valueOf(1).equals(object.get("protocolVersion"))) throw new IllegalArgumentException("unsupported_protocol_version");
        String kind = requiredString(object, "kind", 128, false);
        if (!Arrays.asList("clipboard.text", "android.notification").contains(kind)) throw new IllegalArgumentException("unsupported_kind");
        String role = requiredString(object, "originRole", 16, false);
        if (!Arrays.asList("android", "macos").contains(role)) throw new IllegalArgumentException("invalid_role");
        if ("macos".equals(role) && !"clipboard.text".equals(kind)) throw new IllegalArgumentException("direction_forbidden");
        long sequence = requiredLong(object, "sequence", 1);
        long created = requiredLong(object, "createdAtMs", 0);
        Long expires = optionalLong(object, "expiresAtMs", created);
        Object rawPayload = object.get("payload");
        if (!(rawPayload instanceof Map)) throw new IllegalArgumentException("invalid_payload");
        Map<String, Object> input = (Map<String, Object>) rawPayload;
        Map<String, String> payload = new LinkedHashMap<>();
        if ("clipboard.text".equals(kind)) {
            payload.put("text", requiredString(input, "text", 1_048_576, true));
        } else {
            String[] fields = {"notificationKey", "packageName", "appLabel", "title", "body"};
            int[] limits = {4096, 255, 4096, 8192, 65536};
            for (int index = 0; index < fields.length; index++) payload.put(fields[index], requiredString(input, fields[index], limits[index], true));
            if (MiniJson.encode(payload).getBytes(StandardCharsets.UTF_8).length > 81_920) throw new IllegalArgumentException("notification_payload_too_large");
        }
        return new ProtocolEvent(requiredString(object, "eventId", 128, false),
                requiredString(object, "originDeviceId", 128, false), role,
                requiredString(object, "originEpoch", 128, false), sequence, kind, created, expires, payload);
    }

    public static String encode(ProtocolEvent event) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("protocolVersion", 1L);
        value.put("eventId", event.eventId());
        value.put("originDeviceId", event.deviceId());
        value.put("originRole", event.role());
        value.put("originEpoch", event.epoch());
        value.put("sequence", event.sequence());
        value.put("kind", event.kind());
        value.put("createdAtMs", event.createdAtMs());
        if (event.expiresAtMs() != null) value.put("expiresAtMs", event.expiresAtMs());
        value.put("payload", event.payload());
        String encoded = MiniJson.encode(value);
        decode(encoded);
        return encoded;
    }

    private static String requiredString(Map<String, Object> object, String key, int limit, boolean empty) {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("invalid_" + key);
        String text = (String) value;
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if ((!empty && text.isEmpty()) || bytes > limit) throw new IllegalArgumentException("invalid_" + key);
        return text;
    }

    private static long requiredLong(Map<String, Object> object, String key, long minimum) {
        Object value = object.get(key);
        if (!(value instanceof Long) || ((Long) value) < minimum || ((Long) value) > MAX_SAFE_INTEGER) throw new IllegalArgumentException("invalid_" + key);
        return (Long) value;
    }

    private static Long optionalLong(Map<String, Object> object, String key, long minimum) {
        if (!object.containsKey(key)) return null;
        return requiredLong(object, key, minimum);
    }
}
