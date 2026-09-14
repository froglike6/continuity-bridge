package com.froglike6.continuitybridge;

import android.util.Log;

final class MetadataLog {
    private static final String TAG = "ContinuityBridge";
    private MetadataLog() { }
    static void clipboardMonitor(String phase) {
        Log.i(TAG, "event=clipboard.monitor phase=" + safe(phase));
    }
    static void state(ConnectionStatus status, String errorClass) {
        Log.i(TAG, "event=connection.state status=" + status.name() + " errorClass=" + safe(errorClass));
    }
    static void transport(String operation, int status, String errorClass) {
        Log.i(TAG, "event=transport.result operation=" + safe(operation) + " status=" + status + " errorClass=" + safe(errorClass));
    }
    static void transportFailure(TransportResponse.FailureMetadata metadata) {
        if (metadata != null) Log.i(TAG, metadata.message());
    }
    private static String safe(String value) { return value == null ? "none" : value.replaceAll("[^A-Za-z0-9_.-]", "_"); }
}
