package com.froglike6.continuitybridge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class CryptoEnvelope {
    private static final byte VERSION = 1;
    private CryptoEnvelope() { }

    public static byte[] seal(SecretKey key, String value, SecureRandom random) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, random);
        byte[] nonce = cipher.getIV();
        if (nonce == null || nonce.length != 12) throw new GeneralSecurityException("invalid_nonce");
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(2 + nonce.length + ciphertext.length).put(VERSION).put((byte) nonce.length).put(nonce).put(ciphertext).array();
    }

    public static String open(SecretKey key, byte[] envelope) throws GeneralSecurityException {
        if (envelope == null || envelope.length < 2 + 12 + 16 || envelope[0] != VERSION) throw new GeneralSecurityException("invalid_envelope");
        int nonceLength = envelope[1] & 0xff;
        if (nonceLength != 12 || envelope.length <= 2 + nonceLength + 15) throw new GeneralSecurityException("invalid_envelope");
        byte[] nonce = new byte[nonceLength];
        byte[] ciphertext = new byte[envelope.length - 2 - nonceLength];
        System.arraycopy(envelope, 2, nonce, 0, nonce.length);
        System.arraycopy(envelope, 2 + nonce.length, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
