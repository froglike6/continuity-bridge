package com.froglike6.continuitybridge;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.net.ssl.HttpsURLConnection;

final class RelayTransport implements BridgeTransport {
    interface Settings {
        String endpoint();
        String pin();
        boolean systemTrust();
    }
    interface ConnectionFactory { HttpsURLConnection open(URL target) throws java.io.IOException; }
    interface DiagnosticSink { void record(TransportResponse.FailureMetadata metadata); }

    private final Context context;
    private final Settings settings;
    private final ConnectionFactory connections;
    private final DiagnosticSink diagnostics;
    private Request active;
    private boolean pollWakePending;
    private volatile boolean cancelled;

    RelayTransport(Context context, final ConfigStore config) {
        this(context, new Settings() {
            @Override public String endpoint() { return config.endpoint(); }
            @Override public String pin() { return config.pin(); }
            @Override public boolean systemTrust() { return config.systemTrust(); }
        }, new ConnectionFactory() {
            @Override public HttpsURLConnection open(URL target) throws java.io.IOException { return (HttpsURLConnection) target.openConnection(); }
        }, new DiagnosticSink() {
            @Override public void record(TransportResponse.FailureMetadata metadata) { MetadataLog.transportFailure(metadata); }
        });
    }

    RelayTransport(Context context, Settings settings, ConnectionFactory connections, DiagnosticSink diagnostics) {
        this.context = context; this.settings = settings; this.connections = connections; this.diagnostics = diagnostics;
    }

    @Override public TransportResponse poll(String token, String cursor) throws Exception {
        return request(token, "GET", "/v1/events?after=" + cursor + "&waitMs=25000", null);
    }
    @Override public TransportResponse publish(String token, ProtocolEvent event) throws Exception { return request(token, "POST", "/v1/events", EventCodec.encode(event)); }
    @Override public TransportResponse acknowledge(String token, String deviceId, java.util.List<String> eventIds) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>(); body.put("protocolVersion", 1L);
        body.put("recipientDeviceId", deviceId); body.put("recipientRole", "android"); body.put("eventIds", eventIds);
        return request(token, "POST", "/v1/acks", MiniJson.encode(body));
    }
    @Override public void cancel() {
        Request request;
        synchronized (this) { cancelled = true; request = active; }
        if (request != null) request.connection.disconnect();
    }

    void wakePoll() {
        Request request;
        synchronized (this) {
            if (cancelled) return;
            request = active;
            if (request == null || !request.poll) { pollWakePending = true; return; }
            request.woken = true;
        }
        request.connection.disconnect();
    }

    private synchronized void checkRequest(Request request) throws java.io.IOException {
        if (cancelled) throw new CancelledTransportException();
        if (request.woken) throw new PollWakeException();
    }

    private static final class Request {
        final HttpsURLConnection connection;
        final boolean poll;
        boolean woken;
        Request(HttpsURLConnection connection, boolean poll) { this.connection = connection; this.poll = poll; }
    }

    private TransportResponse request(String token, String method, String path, String body) throws Exception {
        URI base = ConfigValidator.httpsUrl(settings.endpoint());
        URL target = new URI(base.getScheme(), null, base.getHost(), base.getPort(), path.substring(0, path.indexOf('?') < 0 ? path.length() : path.indexOf('?')),
                path.indexOf('?') < 0 ? null : path.substring(path.indexOf('?') + 1), null).toURL();
        HttpsURLConnection connection = connections.open(target);
        Request request = new Request(connection, "GET".equals(method));
        try {
            synchronized (this) {
                if (cancelled) throw new CancelledTransportException();
                if (request.poll && pollWakePending) { pollWakePending = false; throw new PollWakeException(); }
                active = request;
            }
            if (!settings.systemTrust()) {
                InputStream ca = context.getResources().openRawResource(R.raw.continuity_local_ca);
                try {
                    TlsPolicy policy = TlsPolicy.local(ca, settings.pin(), new Date());
                    connection.setSSLSocketFactory(policy.context().getSocketFactory());
                } finally { ca.close(); }
            }
            connection.setConnectTimeout(10_000); connection.setReadTimeout(30_000);
            connection.setRequestMethod(method); connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8); connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream output = connection.getOutputStream(); try { output.write(bytes); } finally { output.close(); }
            }
            checkRequest(request);
            if (request.poll) {
                // Android ignores disconnect before connect; check again before waiting for response headers.
                connection.connect();
                checkRequest(request);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            TransportResponse response = new TransportResponse(status, stream == null ? "" : read(stream));
            TransportResponse.FailureMetadata failure = TransportResponse.failureMetadata(method, path, status, response.body());
            if (failure != null) diagnostics.record(failure);
            return response;
        } catch (IllegalArgumentException error) { throw error;
        } catch (java.io.IOException error) {
            checkRequest(request);
            throw error;
        } catch (Exception securityError) {
            javax.net.ssl.SSLException failure = new javax.net.ssl.SSLException("tls_policy_setup_failed");
            failure.initCause(securityError); throw failure;
        } finally {
            synchronized (this) { if (active == request) active = null; }
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) { if (output.size() + count > 1_200_000) throw new java.io.IOException("response_too_large"); output.write(buffer, 0, count); }
            return output.toString("UTF-8");
        } finally { input.close(); }
    }
}
