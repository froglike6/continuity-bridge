package com.froglike6.continuitybridge;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class TlsPolicy {
    public enum Mode { LOCAL_CA_PINNED, SYSTEM_TRUST }
    private final X509TrustManager trustManager;
    private final String pin;
    private final Date validationTime;

    private TlsPolicy(X509TrustManager trustManager, String pin, Date validationTime) {
        this.trustManager = trustManager; this.pin = pin; this.validationTime = validationTime;
    }

    public static TlsPolicy local(InputStream caStream, String pin, Date time) throws Exception {
        X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(caStream);
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType()); store.load(null); store.setCertificateEntry("continuity-local-ca", ca);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(store);
        return new TlsPolicy(manager(factory), ConfigValidator.pin(pin), time);
    }

    public static TlsPolicy system() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init((KeyStore) null);
        return new TlsPolicy(manager(factory), null, new Date());
    }

    public SSLContext context() throws Exception {
        final TlsPolicy policy = this;
        X509TrustManager enforcing = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return trustManager.getAcceptedIssuers(); }
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException { trustManager.checkClientTrusted(chain, authType); }
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                try { policy.verifyChain(chain, authType); }
                catch (java.security.cert.CertificateException error) { throw error; }
                catch (Exception error) { throw new java.security.cert.CertificateException("tls_policy_rejected", error); }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, new javax.net.ssl.TrustManager[] { enforcing }, null); return context;
    }

    public void verifyChain(X509Certificate[] chain, String authType) throws Exception {
        trustManager.checkServerTrusted(chain, authType);
        chain[0].checkValidity(validationTime);
        if (pin != null && !pin.equals(sha256(chain[0].getEncoded()))) throw new javax.net.ssl.SSLPeerUnverifiedException("leaf_pin_mismatch");
    }

    public boolean verifyHostname(String host, SSLSession session) {
        HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
        return verifier.verify(host, session);
    }

    public static boolean verifyCertificateHost(X509Certificate certificate, String host) throws Exception {
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        if (names == null) return false;
        boolean ip = host.matches("[0-9a-fA-F:.]+");
        int requiredType = ip ? 7 : 2;
        for (List<?> name : names) {
            if (((Integer) name.get(0)) == requiredType && host.equalsIgnoreCase(String.valueOf(name.get(1)))) return true;
        }
        return false;
    }

    public static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static X509TrustManager manager(TrustManagerFactory factory) {
        for (javax.net.ssl.TrustManager manager : factory.getTrustManagers()) if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        throw new IllegalStateException("x509_trust_manager_missing");
    }
}
