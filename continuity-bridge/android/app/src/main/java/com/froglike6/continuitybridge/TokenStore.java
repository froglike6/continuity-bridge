package com.froglike6.continuitybridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

final class TokenStore implements TokenProvider, AccessCredentials.Provider {
    private static final String ALIAS = "continuity_bridge_token_aes_v1";
    static final String ACCESS_ENVELOPE = "cloudflare_access_envelope";
    private final SharedPreferences ciphertext;
    TokenStore(Context context) { ciphertext = context.getSharedPreferences("bridge_secret_envelope", Context.MODE_PRIVATE); }

    void put(String token) throws Exception {
        putEnvelope("token_envelope", token, "secure_token_persistence_failed");
    }

    String get() throws Exception {
        return getEnvelope("token_envelope");
    }

    @Override public String load() throws SecureStoreException {
        try { return get(); }
        catch (Exception error) { throw new SecureStoreException("secure_token_unavailable", error); }
    }

    void putAccess(AccessCredentials credentials) throws Exception {
        putEnvelope(ACCESS_ENVELOPE, credentials.encode(), "secure_access_persistence_failed");
    }

    @Override public AccessCredentials loadAccess() throws SecureStoreException {
        try {
            String stored = getEnvelope(ACCESS_ENVELOPE);
            return stored == null ? null : AccessCredentials.decode(stored);
        } catch (Exception error) { throw new SecureStoreException("secure_access_unavailable", error); }
    }

    private void putEnvelope(String name, String value, String failure) throws Exception {
        byte[] envelope = CryptoEnvelope.seal(key(true), value, new SecureRandom());
        if (!ciphertext.edit().putString(name, Base64.getEncoder().encodeToString(envelope)).commit()) throw new IOException(failure);
    }

    private String getEnvelope(String name) throws Exception {
        String stored = ciphertext.getString(name, null);
        if (stored == null) return null;
        return CryptoEnvelope.open(key(false), Base64.getDecoder().decode(stored));
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        if (!create) throw new java.security.GeneralSecurityException("secure_key_unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
