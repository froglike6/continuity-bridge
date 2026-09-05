package com.froglike6.continuitybridge;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

final class TestStateCipher implements StateCipher {
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    private TestStateCipher(byte fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, fill);
        key = new SecretKeySpec(bytes, "AES");
    }

    static TestStateCipher create() { return new TestStateCipher((byte) 0x41); }
    static TestStateCipher otherKey() { return new TestStateCipher((byte) 0x72); }

    @Override public byte[] seal(byte[] plaintext) throws GeneralSecurityException {
        return StateEnvelope.seal(key, plaintext, random);
    }

    @Override public byte[] open(byte[] envelope) throws GeneralSecurityException {
        return StateEnvelope.open(key, envelope);
    }
}
