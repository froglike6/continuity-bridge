package com.froglike6.continuitybridge;

public final class TransportDiagnosticSuite {
    private static int cases;
    private TransportDiagnosticSuite() { }

    public static void main(String[] args) throws Exception {
        run();
        System.out.println("TRANSPORT_DIAGNOSTIC_UNIT_OK cases=" + cases + " markers=parser_identity_mismatch,parser_direction_forbidden,parser_malformed_unknown,parser_oversized_unknown,parser_poll_query_stripped,parser_success_silent,parser_privacy_redaction");
    }

    static int run() throws Exception {
        identityMismatch();
        directionForbidden();
        malformedAndOversized();
        pollPathAndSuccess();
        return cases;
    }

    private static void identityMismatch() throws Exception {
        String secret = "RESPONSE_MESSAGE_SENTINEL device-raw-123 token-raw-456 payload-raw";
        Object diagnostic = failure("POST", "/v1/events", 403,
                "{\"error\":{\"code\":\"identity_mismatch\",\"message\":\"" + secret + "\"}}");
        check("POST".equals(value(diagnostic, "method")), "identity method");
        check("/v1/events".equals(value(diagnostic, "path")), "identity path");
        check(Integer.valueOf(403).equals(value(diagnostic, "status")), "identity status");
        check("identity_mismatch".equals(value(diagnostic, "errorCode")), "identity error code");
        String line = (String) value(diagnostic, "message");
        check("event=transport.failure method=POST path=/v1/events status=403 errorCode=identity_mismatch".equals(line), "identity structured line");
        check(!line.contains(secret) && !line.contains("device-raw") && !line.contains("token-raw") && !line.contains("payload-raw"), "identity privacy");
    }

    private static void directionForbidden() throws Exception {
        Object diagnostic = failure("POST", "/v1/acks", 403, "{\"error\":{\"code\":\"direction_forbidden\"}}");
        check("direction_forbidden".equals(value(diagnostic, "errorCode")), "direction error code");
        check("/v1/acks".equals(value(diagnostic, "path")), "direction path");
    }

    private static void malformedAndOversized() throws Exception {
        String secret = "MALFORMED_BODY_SENTINEL";
        Object malformed = failure("POST", "/v1/events", 403, "{\"error\":{\"code\":\"identity_mismatch\",\"message\":\"" + secret);
        check("unknown".equals(value(malformed, "errorCode")), "malformed fallback");
        check(!((String) value(malformed, "message")).contains(secret), "malformed privacy");
        Object oversized = failure("POST", "/v1/events", 403,
                "{\"error\":{\"code\":\"identity_mismatch\",\"message\":\"" + repeat('x', 4096) + "OVERSIZED_BODY_SENTINEL\"}}");
        check("unknown".equals(value(oversized, "errorCode")), "oversized fallback");
        check(!((String) value(oversized, "message")).contains("OVERSIZED_BODY_SENTINEL"), "oversized privacy");
    }

    private static void pollPathAndSuccess() throws Exception {
        Object poll = failure("GET", "/v1/events?after=CURSOR_SENTINEL&waitMs=25000", 403,
                "{\"error\":{\"code\":\"identity_mismatch\"}}");
        check("GET".equals(value(poll, "method")) && "/v1/events".equals(value(poll, "path")), "poll canonical path");
        check(!((String) value(poll, "message")).contains("CURSOR_SENTINEL"), "poll query privacy");
        check(failure("POST", "/v1/events", 201, "{\"error\":{\"code\":\"identity_mismatch\"}}") == null, "success silent");
    }

    private static Object failure(String method, String path, int status, String body) throws Exception {
        return TransportResponse.failureMetadata(method, path, status, body);
    }

    private static Object value(Object diagnostic, String name) throws Exception {
        TransportResponse.FailureMetadata metadata = (TransportResponse.FailureMetadata) diagnostic;
        if ("method".equals(name)) return metadata.method();
        if ("path".equals(name)) return metadata.path();
        if ("status".equals(name)) return Integer.valueOf(metadata.status());
        if ("errorCode".equals(name)) return metadata.errorCode();
        if ("message".equals(name)) return metadata.message();
        throw new IllegalArgumentException(name);
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        cases++;
    }
}
