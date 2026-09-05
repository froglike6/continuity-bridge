package com.froglike6.continuitybridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

final class TokenStore implements TokenProvider {
    private static final String ALIAS = "continuity_bridge_token_aes_v1";
    private final SharedPreferences ciphertext;
    TokenStore(Context context) { ciphertext = context.getSharedPreferences("bridge_secret_envelope", Context.MODE_PRIVATE); }

    void put(String token) throws Exception {
        byte[] envelope = CryptoEnvelope.seal(key(), token, new SecureRandom());
        ciphertext.edit().putString("token_envelope", Base64.getEncoder().encodeToString(envelope)).commit();
    }

    String get() throws Exception {
        String stored = ciphertext.getString("token_envelope", null);
        if (stored == null) return null;
        return CryptoEnvelope.open(key(), Base64.getDecoder().decode(stored));
    }

    @Override public String load() throws SecureStoreException {
        try { return get(); }
        catch (Exception error) { throw new SecureStoreException("secure_token_unavailable", error); }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
