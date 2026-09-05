package com.froglike6.continuitybridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;

public final class RelayTransportDiagnosticSuite {
    private static int cases;
    private RelayTransportDiagnosticSuite() { }

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("RELAY_TRANSPORT_DIAGNOSTIC_OK cases=" + cases + " markers=identity_mismatch,direction_forbidden,malformed_unknown,wrong_type_unknown,missing_code_unknown,oversized_unknown,poll_query_stripped,success_silent,metadata_redacted,exactly_once");
    }

    static int run() throws Exception {
        malformedAndUntrustedBodies();
        publishFailureEmitsOnce();
        acknowledgeDirectionForbidden();
        pollFailureStripsQuery();
        successDoesNotEmit();
        return cases;
    }

    private static void publishFailureEmitsOnce() throws Exception {
        ExchangeFactory factory = new ExchangeFactory(403, "{\"error\":{\"code\":\"identity_mismatch\",\"message\":\"RESPONSE_SENTINEL device-raw token-raw payload-raw\"}}");
        RecordingSink sink = new RecordingSink();
        RelayTransport transport = new RelayTransport(null, new FixedSettings(), factory, sink);
        Map<String, String> payload = new LinkedHashMap<>(); payload.put("text", "PAYLOAD_SENTINEL");
        TransportResponse response = transport.publish("TOKEN_SENTINEL", new ProtocolEvent("event-1", "device-1", "android", "epoch-1", 1, "clipboard.text", 1, null, payload));
        check(response.status() == 403 && response.body().contains("RESPONSE_SENTINEL"), "publish status/body");
        check(factory.connection.requestMethod.equals("POST") && factory.target.getPath().equals("/v1/events"), "publish exchange");
        check(sink.calls == 1, "publish emits once");
        check(sink.message.equals("event=transport.failure method=POST path=/v1/events status=403 errorCode=identity_mismatch"), "publish fields");
        check(!sink.message.contains("SENTINEL") && !sink.message.contains("device-raw") && !sink.message.contains("token-raw") && !sink.message.contains("payload-raw"), "publish redaction");
    }

    private static void acknowledgeDirectionForbidden() throws Exception {
        ExchangeFactory factory = new ExchangeFactory(403, "{\"error\":{\"code\":\"direction_forbidden\",\"message\":\"ACK_RESPONSE_SENTINEL\"}}");
        RecordingSink sink = new RecordingSink();
        RelayTransport transport = new RelayTransport(null, new FixedSettings(), factory, sink);
        transport.acknowledge("TOKEN_SENTINEL", "DEVICE_SENTINEL", java.util.Collections.singletonList("EVENT_SENTINEL"));
        check(factory.connection.requestMethod.equals("POST") && factory.target.getPath().equals("/v1/acks"), "acknowledge exchange");
        check(sink.calls == 1 && sink.message.equals("event=transport.failure method=POST path=/v1/acks status=403 errorCode=direction_forbidden"), "acknowledge diagnostic");
        check(!sink.message.contains("SENTINEL"), "acknowledge redaction");
    }

    private static void malformedAndUntrustedBodies() throws Exception {
        assertUnknownPublish("{\"error\":{\"code\":\"identity_mismatch\",\"message\":\"MALFORMED_SENTINEL", "MALFORMED_SENTINEL", "malformed fallback");
        assertUnknownPublish("{\"error\":{\"code\":{\"nested\":\"identity_mismatch\"},\"message\":\"WRONG_TYPE_SENTINEL\"}}", "WRONG_TYPE_SENTINEL", "wrong type fallback");
        assertUnknownPublish("{\"error\":{\"message\":\"MISSING_CODE_SENTINEL\"}}", "MISSING_CODE_SENTINEL", "missing code fallback");
        assertUnknownPublish("{\"error\":{\"code\":\"identity_mismatch\",\"message\":\"" + repeat('x', 4096) + "OVERSIZED_SENTINEL\"}}", "OVERSIZED_SENTINEL", "oversized fallback");
    }

    private static void assertUnknownPublish(String body, String sentinel, String name) throws Exception {
        ExchangeFactory factory = new ExchangeFactory(403, body);
        RecordingSink sink = new RecordingSink();
        TransportResponse response = new RelayTransport(null, new FixedSettings(), factory, sink).publish("TOKEN_SENTINEL", event());
        check(response.body().contains(sentinel), name + " response retained");
        check(sink.calls == 1, name + " emits once");
        check(sink.message.equals("event=transport.failure method=POST path=/v1/events status=403 errorCode=unknown"), name + " diagnostic");
        check(!sink.message.contains(sentinel) && !sink.message.contains("TOKEN_SENTINEL"), name + " redaction");
    }

    private static void pollFailureStripsQuery() throws Exception {
        ExchangeFactory factory = new ExchangeFactory(403, "{\"error\":{\"code\":\"identity_mismatch\",\"message\":\"POLL_RESPONSE_SENTINEL\"}}");
        RecordingSink sink = new RecordingSink();
        RelayTransport transport = new RelayTransport(null, new FixedSettings(), factory, sink);
        transport.poll("TOKEN_SENTINEL", "CURSOR_SENTINEL");
        check(factory.target.getQuery().contains("CURSOR_SENTINEL"), "poll exchange query");
        check(sink.calls == 1 && sink.message.equals("event=transport.failure method=GET path=/v1/events status=403 errorCode=identity_mismatch"), "poll canonical diagnostic");
        check(!sink.message.contains("CURSOR_SENTINEL") && !sink.message.contains("TOKEN_SENTINEL") && !sink.message.contains("POLL_RESPONSE_SENTINEL"), "poll redaction");
    }

    private static void successDoesNotEmit() throws Exception {
        ExchangeFactory factory = new ExchangeFactory(200, "{\"entries\":[]}");
        RecordingSink sink = new RecordingSink();
        RelayTransport transport = new RelayTransport(null, new FixedSettings(), factory, sink);
        check(transport.poll("TOKEN_SENTINEL", "0").status() == 200 && sink.calls == 0, "success silent");
    }

    private static ProtocolEvent event() {
        return new ProtocolEvent("event-1", "device-1", "android", "epoch-1", 1, "clipboard.text", 1, null, java.util.Collections.singletonMap("text", "payload"));
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }

    private static final class FixedSettings implements RelayTransport.Settings {
        @Override public String endpoint() { return "https://relay.example:8443"; }
        @Override public String pin() { return ""; }
        @Override public boolean systemTrust() { return true; }
    }

    private static final class RecordingSink implements RelayTransport.DiagnosticSink {
        private int calls;
        private String message;
        @Override public void record(TransportResponse.FailureMetadata metadata) { calls++; message = metadata.message(); }
    }

    private static final class ExchangeFactory implements RelayTransport.ConnectionFactory {
        private final int status;
        private final String body;
        private URL target;
        private FakeConnection connection;
        ExchangeFactory(int status, String body) { this.status = status; this.body = body; }
        @Override public HttpsURLConnection open(URL target) {
            this.target = target; connection = new FakeConnection(target, status, body); return connection;
        }
    }

    private static final class FakeConnection extends HttpsURLConnection {
        private final int status;
        private final String body;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private String requestMethod;
        FakeConnection(URL target, int status, String body) { super(target); this.status = status; this.body = body; }
        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return ""; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() throws SSLPeerUnverifiedException { return null; }
        @Override public Principal getPeerPrincipal() throws SSLPeerUnverifiedException { return null; }
        @Override public int getResponseCode() { return status; }
        @Override public java.io.InputStream getErrorStream() { return new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        @Override public java.io.InputStream getInputStream() { return new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        @Override public ByteArrayOutputStream getOutputStream() { return output; }
        @Override public void setRequestMethod(String method) { requestMethod = method; }
        @Override public String getRequestMethod() { return requestMethod; }
        @Override public void setRequestProperty(String key, String value) { headers.put(key, value); }
        @Override public String getRequestProperty(String key) { return headers.get(key); }
    }

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        cases++;
    }
}
