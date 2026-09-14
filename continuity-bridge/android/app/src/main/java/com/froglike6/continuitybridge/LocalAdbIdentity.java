package com.froglike6.continuitybridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.x509.X509V3CertificateGenerator;

final class LocalAdbIdentity {
    static final class Identity {
        final java.security.PrivateKey privateKey;
        final java.security.cert.Certificate certificate;
        Identity(java.security.PrivateKey privateKey, java.security.cert.Certificate certificate) {
            this.privateKey = privateKey; this.certificate = certificate;
        }
    }
    private static final String ALIAS = "continuity_bridge_local_adb_aes_v1";
    private final SharedPreferences store;
    LocalAdbIdentity(Context context) {
        store = context.getSharedPreferences("bridge_local_adb_identity", Context.MODE_PRIVATE);
    }

    synchronized Identity load() throws Exception {
        String stored = store.getString("encrypted_identity", null);
        if (stored != null) {
            String clear = CryptoEnvelope.open(key(false), Base64.getDecoder().decode(stored));
            String[] fields = clear.split("\n", -1);
            if (fields.length != 2) throw new java.security.GeneralSecurityException("adb_identity_invalid");
            byte[] privateBytes = Base64.getDecoder().decode(fields[0]);
            try {
                return new Identity(KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateBytes)),
                        CertificateFactory.getInstance("X.509").generateCertificate(
                                new ByteArrayInputStream(Base64.getDecoder().decode(fields[1]))));
            } finally { Arrays.fill(privateBytes, (byte) 0); }
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair pair = generator.generateKeyPair();
        X509V3CertificateGenerator certificate = new X509V3CertificateGenerator();
        X500Principal name = new X500Principal("CN=Continuity Bridge");
        certificate.setSerialNumber(new BigInteger(128, new SecureRandom()).add(BigInteger.ONE));
        certificate.setIssuerDN(name);
        certificate.setSubjectDN(name);
        certificate.setNotBefore(new Date(System.currentTimeMillis() - 86_400_000L));
        certificate.setNotAfter(new Date(System.currentTimeMillis() + 10L * 365 * 86_400_000L));
        certificate.setPublicKey(pair.getPublic());
        certificate.setSignatureAlgorithm("SHA256WithRSA");
        java.security.cert.Certificate signed = certificate.generate(pair.getPrivate());
        byte[] encoded = pair.getPrivate().getEncoded();
        try {
            String clear = Base64.getEncoder().encodeToString(encoded) + "\n"
                    + Base64.getEncoder().encodeToString(signed.getEncoded());
            byte[] envelope = CryptoEnvelope.seal(key(true), clear, new SecureRandom());
            if (!store.edit().putString("encrypted_identity", Base64.getEncoder().encodeToString(envelope)).commit())
                throw new java.io.IOException("adb_identity_save_failed");
        } finally { Arrays.fill(encoded, (byte) 0); }
        return new Identity(pair.getPrivate(), signed);
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        java.security.Key existing = keys.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        if (!create) throw new java.security.GeneralSecurityException("adb_identity_key_missing");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
