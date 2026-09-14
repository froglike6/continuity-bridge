package com.froglike6.continuitybridge;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class ProtocolPayloads {
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private ProtocolPayloads() { }

    static Map<String, Object> parse(String kind, Map<String, Object> input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (kind) {
            case "clipboard.text": payload.put("text", string(input, "text", 1_048_576)); break;
            case "clipboard.image":
                String mime = string(input, "mimeType", 32);
                String base64 = string(input, "dataBase64", WireLimits.IMAGE_ENCODED_BYTES);
                byte[] data = decode(base64, WireLimits.IMAGE_ENCODED_BYTES, WireLimits.IMAGE_DECODED_BYTES);
                if (!("image/png".equals(mime) && starts(data, PNG)) &&
                    !("image/jpeg".equals(mime) && starts(data, new byte[] {(byte) 255, (byte) 216, (byte) 255}))) {
                    throw new IllegalArgumentException("invalid_image");
                }
                payload.put("mimeType", mime); payload.put("dataBase64", base64); break;
            case "android.notification": notification(input, payload); break;
            default: throw new IllegalArgumentException("unsupported_kind");
        }
        return payload;
    }

    private static void notification(Map<String, Object> input, Map<String, Object> payload) {
        String[] fields = {"notificationKey", "packageName", "appLabel", "title", "body"};
        int[] limits = {4096, 255, 4096, 8192, 65536};
        for (int index = 0; index < fields.length; index++) payload.put(fields[index], string(input, fields[index], limits[index]));
        if (input.containsKey("iconPngBase64")) {
            String icon = string(input, "iconPngBase64", 16_384); byte[] bytes = decode(icon, 16_384, 12_288);
            if (bytes.length < 33 || !starts(bytes, PNG) || uint32(bytes, 8) != 13 ||
                    bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R' ||
                    uint32(bytes, 16) < 1 || uint32(bytes, 16) > 128 || uint32(bytes, 20) < 1 || uint32(bytes, 20) > 128) {
                throw new IllegalArgumentException("invalid_icon");
            }
            payload.put("iconPngBase64", icon);
        }
        if (input.containsKey("progress")) payload.put("progress", progress(input.get("progress")));
        for (String field : new String[] {"isOngoing", "isRedacted"}) {
            if (input.containsKey(field)) {
                if (!(input.get(field) instanceof Boolean)) throw new IllegalArgumentException("invalid_" + field);
                payload.put(field, input.get(field));
            }
        }
        if (input.containsKey("category")) payload.put("category", string(input, "category", 128));
        if (MiniJson.encode(payload).getBytes(StandardCharsets.UTF_8).length > WireLimits.NOTIFICATION_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("notification_payload_too_large");
        }
    }

    private static Map<String, Object> progress(Object raw) {
        if (!(raw instanceof Map)) throw new IllegalArgumentException("invalid_progress");
        Map<?, ?> input = (Map<?, ?>) raw;
        long value = integer(input.get("value")); long max = integer(input.get("max"));
        if (!(input.get("indeterminate") instanceof Boolean) || value > max ||
                (max == 0 && !Boolean.TRUE.equals(input.get("indeterminate")))) throw new IllegalArgumentException("invalid_progress");
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("value", value); progress.put("max", max); progress.put("indeterminate", input.get("indeterminate"));
        return progress;
    }

    private static long integer(Object raw) {
        if (!(raw instanceof Long) && !(raw instanceof Integer)) throw new IllegalArgumentException("invalid_progress");
        long value = ((Number) raw).longValue();
        if (value < 0 || value > 2_147_483_647) throw new IllegalArgumentException("invalid_progress");
        return value;
    }

    private static String string(Map<String, Object> input, String key, int limit) {
        Object raw = input.get(key);
        if (!(raw instanceof String) || ((String) raw).getBytes(StandardCharsets.UTF_8).length > limit) {
            throw new IllegalArgumentException("invalid_" + key);
        }
        return (String) raw;
    }

    private static byte[] decode(String value, int encodedLimit, int decodedLimit) {
        if (value.length() > encodedLimit) throw new IllegalArgumentException("image_too_large");
        byte[] data = Base64.getDecoder().decode(value);
        if (data.length > decodedLimit) throw new IllegalArgumentException("image_too_large");
        if (!Base64.getEncoder().encodeToString(data).equals(value)) throw new IllegalArgumentException("invalid_base64");
        return data;
    }

    private static boolean starts(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) if (data[index] != prefix[index]) return false;
        return true;
    }

    private static long uint32(byte[] bytes, int offset) {
        long value = 0;
        for (int index = offset; index < offset + 4; index++) value = (value << 8) | (bytes[index] & 0xff);
        return value;
    }
}
