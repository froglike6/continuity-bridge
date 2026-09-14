package com.froglike6.continuitybridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

public final class AccessTransportSuite {
    private static int cases;
    private AccessTransportSuite() { }

    static int run() throws Exception {
        headersForEveryRequest(true);
        headersForEveryRequest(false);
        missingOrCorruptCredentialsFailBeforeConnection(false);
        missingOrCorruptCredentialsFailBeforeConnection(true);
        for (int code : new int[] { 301, 302, 303, 307, 308, 401, 403 }) terminalResponse(code);
        System.out.println("ACCESS_TRANSPORT_OK cases=" + cases);
        return cases;
    }

    private static void headersForEveryRequest(final boolean enabled) throws Exception {
        Factory factory = new Factory(200);
        final int[] reads = { 0 };
        RelayTransport transport = transport(enabled, factory, new AccessCredentials.Provider() {
            @Override public AccessCredentials loadAccess() {
                reads[0]++;
                if (!enabled) throw new AssertionError("disabled Access read credentials");
                return new AccessCredentials("device.access", "cfast_private");
            }
        });
        for (int method = 0; method < 3; method++) {
            exchange(transport, method);
            FakeConnection connection = factory.connection;
            check("Bearer relay-token".equals(connection.headers.get("Authorization")), "relay authentication retained");
            check(enabled ? "device.access".equals(connection.headers.get("CF-Access-Client-Id"))
                    && "cfast_private".equals(connection.headers.get("CF-Access-Client-Secret"))
                    : !connection.headers.containsKey("CF-Access-Client-Id") && !connection.headers.containsKey("CF-Access-Client-Secret"), "Access headers honor enabled setting");
            check(connection.redirectPolicySet && !connection.getInstanceFollowRedirects(), "redirects explicitly disabled before request");
        }
        check(reads[0] == (enabled ? 3 : 0), "credential loading follows request policy");
    }

    private static void missingOrCorruptCredentialsFailBeforeConnection(final boolean corrupt) throws Exception {
        Factory factory = new Factory(200);
        RelayTransport transport = transport(true, factory, new AccessCredentials.Provider() {
            @Override public AccessCredentials loadAccess() throws SecureStoreException {
                if (corrupt) throw new SecureStoreException("secure_access_unavailable");
                return null;
            }
        });
        try { transport.poll("relay-token", "0"); throw new AssertionError("unavailable credentials sent request"); }
        catch (AccessAuthenticationException expected) { check(!corrupt, "missing credential is authentication failure"); }
        catch (SecureStoreException expected) { check(corrupt, "corrupt credential remains secure storage failure"); }
        check(factory.opened == 0, "no connection opened without enabled credentials");
    }

    private static void terminalResponse(int code) throws Exception {
        Factory factory = new Factory(code);
        factory.failBodyRead = true;
        RelayTransport transport = transport(true, factory, new AccessCredentials.Provider() {
            @Override public AccessCredentials loadAccess() { return new AccessCredentials("device.access", "secret"); }
        });
        TransportResponse response = transport.poll("relay-token", "0");
        check(response.status() == code, "authentication status survives unavailable response body");
        check(factory.opened == 1 && factory.connection.redirectPolicySet && !factory.connection.getInstanceFollowRedirects(), "redirect is never followed");
        check(new RetryPolicy(1, 1, new java.util.Random(1)).classify(code, null) == RetryPolicy.Decision.AUTH_TERMINAL, "redirect and auth response terminal");
    }

    private static RelayTransport transport(final boolean enabled, Factory factory, AccessCredentials.Provider credentials) {
        return new RelayTransport(null, new RelayTransport.Settings() {
            @Override public String endpoint() { return "https://relay.example"; }
            @Override public String pin() { return ""; }
            @Override public boolean systemTrust() { return true; }
            @Override public boolean accessEnabled() { return enabled; }
        }, factory, new RelayTransport.DiagnosticSink() {
            @Override public void record(TransportResponse.FailureMetadata metadata) {
                if (metadata.message().contains("private") || metadata.message().contains("device.access")) throw new AssertionError("credential in diagnostic");
            }
        }, credentials);
    }

    private static void exchange(RelayTransport transport, int method) throws Exception {
        if (method == 0) transport.poll("relay-token", "0");
        if (method == 1) transport.publish("relay-token", new ProtocolEvent("event", "device", "android", "epoch", 1, "clipboard.text", 1, null, Collections.singletonMap("text", "payload")));
        if (method == 2) transport.acknowledge("relay-token", "device", Collections.singletonList("event"));
    }

    private static final class Factory implements RelayTransport.ConnectionFactory {
        private int code;
        private int opened;
        private boolean failBodyRead;
        private FakeConnection connection;
        Factory(int code) { this.code = code; }
        @Override public HttpsURLConnection open(URL target) {
            opened++;
            connection = new FakeConnection(target, code, failBodyRead);
            return connection;
        }
    }

    private static final class FakeConnection extends HttpsURLConnection {
        private final int code;
        private final boolean failBodyRead;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private boolean redirectPolicySet;
        FakeConnection(URL target, int code, boolean failBodyRead) { super(target); this.code = code; this.failBodyRead = failBodyRead; }
        @Override public void setInstanceFollowRedirects(boolean follow) { redirectPolicySet = true; super.setInstanceFollowRedirects(follow); }
        @Override public void connect() { assertRedirectPolicy(); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return ""; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
        @Override public int getResponseCode() { assertRedirectPolicy(); return code; }
        @Override public java.io.InputStream getInputStream() throws IOException {
            if (failBodyRead) throw new IOException("response_body_unavailable");
            return new ByteArrayInputStream("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        @Override public java.io.InputStream getErrorStream() {
            if (!failBodyRead) return new ByteArrayInputStream("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new java.io.InputStream() { @Override public int read() throws IOException { throw new IOException("response_body_unavailable"); } };
        }
        @Override public ByteArrayOutputStream getOutputStream() { assertRedirectPolicy(); return new ByteArrayOutputStream(); }
        @Override public void setRequestProperty(String key, String value) { headers.put(key, value); }
        private void assertRedirectPolicy() { if (!redirectPolicySet || getInstanceFollowRedirects()) throw new AssertionError("request permits redirects"); }
    }

    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); cases++; }
}
