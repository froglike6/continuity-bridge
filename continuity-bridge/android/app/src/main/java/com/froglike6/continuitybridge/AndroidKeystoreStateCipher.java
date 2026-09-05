package com.froglike6.continuitybridge;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

final class AndroidKeystoreStateCipher implements StateCipher {
    private static final String ALIAS = "continuity_bridge_state_aes_v1";
    private final SecureRandom random = new SecureRandom();

    @Override public byte[] seal(byte[] plaintext) throws GeneralSecurityException {
        return StateEnvelope.seal(key(), plaintext, random);
    }

    @Override public byte[] open(byte[] envelope) throws GeneralSecurityException {
        return StateEnvelope.open(key(), envelope);
    }

    private SecretKey key() throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            java.security.Key existing = store.getKey(ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        } catch (GeneralSecurityException error) {
            throw error;
        } catch (Exception error) {
            throw new GeneralSecurityException("state_key_unavailable", error);
        }
    }
}
