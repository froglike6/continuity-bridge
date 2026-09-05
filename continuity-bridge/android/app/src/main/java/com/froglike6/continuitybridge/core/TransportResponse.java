package com.froglike6.continuitybridge;

import java.util.Map;

public final class TransportResponse {
    private static final int MAX_ERROR_BODY_CHARS = 4096;
    private final int status;
    private final String body;
    public TransportResponse(int status, String body) { this.status = status; this.body = body; }
    public int status() { return status; }
    public String body() { return body; }

    public static FailureMetadata failureMetadata(String method, String path, int status, String body) {
        if (status >= 200 && status < 300) return null;
        return new FailureMetadata(canonicalMethod(method), canonicalPath(path), status, relayErrorCode(body));
    }

    private static String canonicalMethod(String method) {
        return "GET".equals(method) || "POST".equals(method) ? method : "unknown";
    }

    private static String canonicalPath(String path) {
        if (path == null) return "unknown";
        int query = path.indexOf('?');
        String canonical = query < 0 ? path : path.substring(0, query);
        return "/v1/events".equals(canonical) || "/v1/acks".equals(canonical) ? canonical : "unknown";
    }

    private static String relayErrorCode(String body) {
        if (body == null || body.length() > MAX_ERROR_BODY_CHARS) return "unknown";
        try {
            Object root = MiniJson.parse(body);
            if (!(root instanceof Map)) return "unknown";
            Object error = ((Map<?, ?>) root).get("error");
            if (!(error instanceof Map)) return "unknown";
            Object code = ((Map<?, ?>) error).get("code");
            if (!(code instanceof String) || !((String) code).matches("[a-z][a-z0-9_]{0,63}")) return "unknown";
            return (String) code;
        } catch (IllegalArgumentException error) { return "unknown"; }
    }

    public static final class FailureMetadata {
        private final String method;
        private final String path;
        private final int status;
        private final String errorCode;

        private FailureMetadata(String method, String path, int status, String errorCode) {
            this.method = method; this.path = path; this.status = status; this.errorCode = errorCode;
        }

        public String method() { return method; }
        public String path() { return path; }
        public int status() { return status; }
        public String errorCode() { return errorCode; }
        public String message() {
            return "event=transport.failure method=" + method + " path=" + path + " status=" + status + " errorCode=" + errorCode;
        }
    }
}
