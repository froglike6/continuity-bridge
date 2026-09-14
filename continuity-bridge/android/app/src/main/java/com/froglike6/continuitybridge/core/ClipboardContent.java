package com.froglike6.continuitybridge;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class ClipboardContent {
    static final int MAX_IMAGE_BYTES = 8_388_608;
    static final int MAX_IMAGE_BASE64 = 11_184_812;
    private final String kind;
    private final Map<String, String> payload;
    private final String fingerprint;

    private ClipboardContent(String kind, Map<String, String> payload, String fingerprint) {
        this.kind = kind; this.payload = Collections.unmodifiableMap(payload); this.fingerprint = fingerprint;
    }

    static ClipboardContent text(String text) {
        if (text == null || Utf8.size(text) > 1_048_576) throw new IllegalArgumentException("clipboard_text_size");
        Map<String, String> payload = new LinkedHashMap<>(); payload.put("text", text);
        return new ClipboardContent("clipboard.text", payload, text);
    }

    static ClipboardContent image(String mimeType, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES)
            throw new IllegalArgumentException("image_size");
        boolean png = "image/png".equals(mimeType) && bytes.length >= 8
                && bytes[0] == (byte) 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10;
        boolean jpeg = "image/jpeg".equals(mimeType) && bytes.length >= 3
                && bytes[0] == (byte) 255 && bytes[1] == (byte) 216 && bytes[2] == (byte) 255;
        if (!png && !jpeg) throw new IllegalArgumentException("image_format");
        Map<String, String> payload = new LinkedHashMap<>(); payload.put("mimeType", mimeType);
        payload.put("dataBase64", Base64.getEncoder().encodeToString(bytes));
        try {
            String digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new ClipboardContent("clipboard.image", payload, mimeType + ":" + digest);
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("sha256_unavailable", unavailable); }
    }

    static ClipboardContent fromEvent(ProtocolEvent event) {
        if ("clipboard.text".equals(event.kind())) return text(event.payload().get("text"));
        if (!"clipboard.image".equals(event.kind())) throw new IllegalArgumentException("clipboard_kind");
        String encoded = event.payload().get("dataBase64");
        if (encoded == null || encoded.length() > MAX_IMAGE_BASE64) throw new IllegalArgumentException("image_size");
        ClipboardContent image = image(event.payload().get("mimeType"), Base64.getDecoder().decode(encoded));
        if (!encoded.equals(image.payload.get("dataBase64"))) throw new IllegalArgumentException("image_base64");
        return image;
    }

    String kind() { return kind; }
    boolean isImage() { return "clipboard.image".equals(kind); }
    String text() { return payload.get("text"); }
    String mimeType() { return payload.get("mimeType"); }
    byte[] bytes() { return Base64.getDecoder().decode(payload.get("dataBase64")); }
    Map<String, String> payload() { return payload; }
    String fingerprint() { return fingerprint; }
}
