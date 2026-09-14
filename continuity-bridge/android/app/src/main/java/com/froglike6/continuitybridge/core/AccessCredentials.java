package com.froglike6.continuitybridge;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AccessCredentials {
    public enum Field { CLIENT_ID, CLIENT_SECRET }
    public interface Provider { AccessCredentials loadAccess() throws SecureStoreException; }
    public static final class ValidationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final Field field;
        ValidationException(Field field) { super("invalid_access_credential"); this.field = field; }
        public Field field() { return field; }
    }

    private final String clientId;
    private final String clientSecret;

    public AccessCredentials(String clientId, String clientSecret) {
        this.clientId = headerValue(clientId, Field.CLIENT_ID);
        this.clientSecret = headerValue(clientSecret, Field.CLIENT_SECRET);
    }

    public String clientId() { return clientId; }
    public String clientSecret() { return clientSecret; }
    static void validateClientId(String value) { headerValue(value, Field.CLIENT_ID); }

    public static AccessCredentials forSave(String clientId, String enteredSecret, AccessCredentials saved) {
        headerValue(clientId, Field.CLIENT_ID);
        if (!enteredSecret.isEmpty()) return new AccessCredentials(clientId, enteredSecret);
        if (saved != null && saved.clientId.equals(clientId)) return saved;
        throw new ValidationException(Field.CLIENT_SECRET);
    }

    public static AccessCredentials required(boolean enabled, Provider provider) throws SecureStoreException, AccessAuthenticationException {
        if (!enabled) return null;
        AccessCredentials credentials = provider.loadAccess();
        if (credentials == null) throw new AccessAuthenticationException();
        return credentials;
    }

    String encode() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", 1L);
        value.put("purpose", "cloudflare_access");
        value.put("clientId", clientId);
        value.put("clientSecret", clientSecret);
        return MiniJson.encode(value);
    }

    static AccessCredentials decode(String encoded) {
        Object parsed = MiniJson.parse(encoded);
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("invalid_access_envelope");
        Map<?, ?> value = (Map<?, ?>) parsed;
        if (!Long.valueOf(1L).equals(value.get("version")) || !"cloudflare_access".equals(value.get("purpose"))
                || !(value.get("clientId") instanceof String) || !(value.get("clientSecret") instanceof String)) {
            throw new IllegalArgumentException("invalid_access_envelope");
        }
        return new AccessCredentials((String) value.get("clientId"), (String) value.get("clientSecret"));
    }

    private static String headerValue(String value, Field field) {
        if (value == null || value.isEmpty() || value.length() > 4096) throw new ValidationException(field);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) throw new ValidationException(field);
        }
        return value;
    }
}
