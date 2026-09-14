package com.froglike6.continuitybridge;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RichProtocolSuite {
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jkWQAAAAASUVORK5CYII=";
    private RichProtocolSuite() { }

    public static void main(String[] arguments) {
        notificationMetadata(); rejectedMetadata(); imageBoundaries(); recipientImage(); normalization();
        System.out.println("RICH_PROTOCOL_SUITE_OK cases=5");
    }

    private static void notificationMetadata() {
        // Given
        String payload = notificationFields() + ",\"iconPngBase64\":\"" + PNG + "\",\"isOngoing\":true,\"isRedacted\":false,\"category\":\"\","
                + "\"progress\":{\"value\":3,\"max\":10,\"indeterminate\":false,\"future\":42}";
        // When
        ProtocolEvent event = EventCodec.decode(json("android.notification", "android", "{" + payload + "}"));
        ProtocolEvent again = EventCodec.decode(EventCodec.encode(event));
        // Then
        require(Boolean.TRUE.equals(again.wirePayload().get("isOngoing")), "typed boolean lost");
        require(Boolean.FALSE.equals(again.wirePayload().get("isRedacted")), "false boolean lost");
        require(again.wirePayload().get("progress") instanceof Map, "progress flattened");
        require(!EventCodec.encode(again).contains("future"), "nested unknown retained");
        require(PNG.equals(again.payload().get("iconPngBase64")), "old string getters broken");
    }

    private static void rejectedMetadata() {
        // Given
        String[] fields = {"\"isOngoing\":null", "\"isRedacted\":\"true\"", "\"category\":null",
            "\"progress\":{\"value\":0,\"max\":0,\"indeterminate\":false}",
            "\"progress\":{\"value\":1,\"max\":0,\"indeterminate\":true}",
            "\"progress\":{\"value\":0,\"max\":2147483648,\"indeterminate\":true}", "\"iconPngBase64\":\"AB==\""};
        // When / Then
        for (String field : fields) rejected(json("android.notification", "android", "{" + notificationFields() + "," + field + "}"));
    }

    private static void imageBoundaries() {
        // Given
        byte[] data = new byte[8_388_608]; byte[] png = Base64.getDecoder().decode(PNG);
        System.arraycopy(png, 0, data, 0, png.length);
        String atLimit = Base64.getEncoder().encodeToString(data);
        // When
        ProtocolEvent event = EventCodec.decode(json("clipboard.image", "android", image("image/png", atLimit)));
        // Then
        require(atLimit.equals(event.payload().get("dataBase64")), "image bytes changed");
        require(EventCodec.encode(event).getBytes(StandardCharsets.UTF_8).length < 12_582_912, "event cap mismatch");
        rejected(json("clipboard.image", "android", image("image/jpeg", PNG)));
        rejected(json("clipboard.image", "android", image("image/png", PNG + " ")));
        rejected(json("clipboard.image", "android", image("image/png", Base64.getEncoder().encodeToString(new byte[8_388_609]))));
    }

    private static void recipientImage() {
        // Given
        byte[] data = new byte[1_100_000]; byte[] png = Base64.getDecoder().decode(PNG); System.arraycopy(png, 0, data, 0, png.length);
        String event = json("clipboard.image", "macos", image("image/png", Base64.getEncoder().encodeToString(data)));
        String response = "{\"protocolVersion\":1,\"serverEpoch\":\"0123456789abcdef0123456789abcdef\",\"after\":\"0\",\"nextCursor\":\"1\",\"events\":[{\"cursor\":\"1\",\"event\":" + event + "}]}";
        // When
        RelayProtocol.Fetch fetch = RelayProtocol.fetch(response, "0");
        // Then
        require(fetch.entries().size() == 1 && "clipboard.image".equals(fetch.entries().get(0).event().kind()), "recipient image rejected");
    }

    private static void normalization() {
        // Given
        Map<String, Object> progress = new LinkedHashMap<>(); progress.put("value", 0L); progress.put("max", 0L); progress.put("indeterminate", true);
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String field : new String[] {"notificationKey", "packageName", "appLabel", "title", "body"}) payload.put(field, field);
        payload.put("progress", progress);
        ProtocolEvent event = new ProtocolEvent("event", "sender", "android", "epoch", 1, "android.notification", 0, null, payload);
        // When
        progress.put("value", 9L);
        // Then
        require(EventCodec.encode(event).contains("\"value\":0"), "nested metadata remained mutable");
    }

    private static String image(String mime, String data) { return "{\"mimeType\":\"" + mime + "\",\"dataBase64\":\"" + data + "\"}"; }
    private static String notificationFields() { return "\"notificationKey\":\"k\",\"packageName\":\"p\",\"appLabel\":\"a\",\"title\":\"t\",\"body\":\"b\""; }
    private static String json(String kind, String role, String payload) {
        return "{\"protocolVersion\":1,\"eventId\":\"event\",\"originDeviceId\":\"sender\",\"originRole\":\"" + role
            + "\",\"originEpoch\":\"epoch\",\"sequence\":1,\"createdAtMs\":0,\"kind\":\"" + kind + "\",\"payload\":" + payload + "}";
    }
    private static void rejected(String source) {
        try { EventCodec.decode(source); throw new AssertionError("invalid rich payload accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
