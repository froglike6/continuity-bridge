package com.froglike6.continuitybridge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class StateEnvelope {
    private static final byte[] MAGIC = new byte[] { 'C', 'B', 'S', 'E' };
    private static final byte VERSION = 1;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final byte[] DOMAIN = "continuity-bridge-state-envelope".getBytes(StandardCharsets.UTF_8);

    private StateEnvelope() { }

    public static byte[] seal(SecretKey key, byte[] plaintext, SecureRandom random) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] header = header();
        cipher.init(Cipher.ENCRYPT_MODE, key, random);
        byte[] nonce = cipher.getIV();
        if (nonce == null || nonce.length != NONCE_LENGTH) throw invalidEnvelope();
        cipher.updateAAD(DOMAIN);
        cipher.updateAAD(header);
        byte[] ciphertext = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(header.length + nonce.length + ciphertext.length)
                .put(header).put(nonce).put(ciphertext).array();
    }

    public static byte[] open(SecretKey key, byte[] envelope) throws GeneralSecurityException {
        int headerLength = MAGIC.length + 2;
        if (envelope == null || envelope.length < headerLength + NONCE_LENGTH + TAG_LENGTH) throw invalidEnvelope();
        byte[] header = Arrays.copyOfRange(envelope, 0, headerLength);
        if (!Arrays.equals(MAGIC, Arrays.copyOfRange(header, 0, MAGIC.length))
                || header[MAGIC.length] != VERSION || (header[MAGIC.length + 1] & 0xff) != NONCE_LENGTH) throw invalidEnvelope();
        byte[] nonce = Arrays.copyOfRange(envelope, headerLength, headerLength + NONCE_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(envelope, headerLength + NONCE_LENGTH, envelope.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(DOMAIN);
        cipher.updateAAD(header);
        return cipher.doFinal(ciphertext);
    }

    private static byte[] header() {
        return ByteBuffer.allocate(MAGIC.length + 2).put(MAGIC).put(VERSION).put((byte) NONCE_LENGTH).array();
    }

    private static GeneralSecurityException invalidEnvelope() {
        return new GeneralSecurityException("invalid_state_envelope");
    }
}
