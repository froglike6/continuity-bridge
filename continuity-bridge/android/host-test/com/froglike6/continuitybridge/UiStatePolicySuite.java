package com.froglike6.continuitybridge;

final class UiStatePolicySuite {
    private UiStatePolicySuite() { }

    static int run() {
        int cases = 0;
        cases += persistedRuntimeStatusRequiresLiveServiceRun();
        cases += processRunPresenceTracksStopAndRestart();
        cases += permissionDiagnosticRequiresMatchingRecovery();
        for (ConnectionStatus status : ConnectionStatus.values()) {
            boolean active = status == ConnectionStatus.CONNECTING || status == ConnectionStatus.CONNECTED
                    || status == ConnectionStatus.CLIPBOARD_WAIT || status == ConnectionStatus.RETRY;
            for (boolean systemTrust : new boolean[] { false, true }) {
                UiStatePolicy.Decision decision = UiStatePolicy.forStatus(status, systemTrust);
                check(decision.startEnabled() == !active, status, systemTrust, "start"); cases++;
                check(decision.stopEnabled() == active, status, systemTrust, "stop"); cases++;
                check(decision.configurationEnabled() == !active, status, systemTrust, "configuration"); cases++;
                check(decision.pinEnabled() == (!active && !systemTrust), status, systemTrust, "pin"); cases++;
            }
        }
        return cases;
    }

    private static int processRunPresenceTracksStopAndRestart() {
        ServiceRunCoordinator process = ServiceRunCoordinator.process();
        check(!process.hasActiveRun(), ConnectionStatus.CONNECTED, false, "new_process_has_no_live_service");
        ServiceRunCoordinator.Run first = process.start();
        try {
            check(first != null && ServiceRunCoordinator.process().hasActiveRun(), ConnectionStatus.CONNECTING, false,
                    "service_and_status_reader_share_process_run");
            check(UiStatePolicy.reconcileServiceStatus(ConnectionStatus.CONNECTED, process.hasActiveRun()) == ConnectionStatus.CONNECTED,
                    ConnectionStatus.CONNECTED, false, "running_service_is_connected");
            check(process.cancel(first) && !process.hasActiveRun(), ConnectionStatus.CONNECTED, false,
                    "cancelled_service_has_no_live_run");
            check(UiStatePolicy.reconcileServiceStatus(ConnectionStatus.CONNECTED, process.hasActiveRun()) == ConnectionStatus.STOPPED,
                    ConnectionStatus.CONNECTED, false, "cancelled_service_cannot_reuse_connected_status");
            ServiceRunCoordinator.Run next = process.start();
            try {
                check(next != null && process.hasActiveRun(), ConnectionStatus.CONNECTING, false, "stopped_service_can_restart");
                check(!process.finish(first) && process.hasActiveRun(), ConnectionStatus.CONNECTING, false,
                        "stale_completion_does_not_hide_restarted_service");
            } finally { process.finish(next); }
            check(!process.hasActiveRun(), ConnectionStatus.STOPPED, false, "finished_service_releases_process_run");
        } finally { process.cancel(first); }
        return 8;
    }

    private static int persistedRuntimeStatusRequiresLiveServiceRun() {
        int cases = 0;
        for (ConnectionStatus persisted : ConnectionStatus.values()) {
            boolean requiresRun = persisted == ConnectionStatus.CONNECTING || persisted == ConnectionStatus.CONNECTED
                    || persisted == ConnectionStatus.CLIPBOARD_WAIT || persisted == ConnectionStatus.RETRY;
            ConnectionStatus restored = UiStatePolicy.reconcileServiceStatus(persisted, false);
            check(restored == (requiresRun ? ConnectionStatus.STOPPED : persisted), persisted, false,
                    "dead_service_cannot_restore_live_status"); cases++;
            check(UiStatePolicy.forStatus(restored, false).startEnabled(), persisted, false,
                    "dead_service_must_allow_start"); cases++;
            check(UiStatePolicy.reconcileServiceStatus(persisted, true) == persisted, persisted, false,
                    "live_service_retains_current_status"); cases++;
        }
        return cases;
    }

    private static int permissionDiagnosticRequiresMatchingRecovery() {
        int cases = 0;
        for (boolean serviceActive : new boolean[] { false, true }) {
            ConnectionStatus refreshed = UiStatePolicy.reconcileServiceStatus(ConnectionStatus.PERMISSION_REQUIRED, serviceActive);
            check(refreshed == ConnectionStatus.PERMISSION_REQUIRED, refreshed, false,
                    "clipboard_failure_survives_refresh_without_a_matching_recovery"); cases++;
            check(UiStatePolicy.forStatus(refreshed, false).startEnabled(), refreshed, false,
                    "retained_permission_failure_allows_manual_recovery"); cases++;
        }
        for (ConnectionStatus status : ConnectionStatus.values()) {
            for (boolean notificationRequest : new boolean[] { false, true }) {
                for (boolean granted : new boolean[] { false, true }) {
                    ConnectionStatus expected = status == ConnectionStatus.PERMISSION_REQUIRED && notificationRequest && granted
                            ? ConnectionStatus.STOPPED : status;
                    check(UiStatePolicy.afterPermissionResult(status, notificationRequest, granted) == expected, status, granted,
                            notificationRequest ? "explicit_notification_callback_recovery" : "unrelated_permission_result_preserves_diagnostic");
                    cases++;
                }
            }
        }
        return cases;
    }

    private static void check(boolean condition, ConnectionStatus status, boolean systemTrust, String control) {
        if (!condition) throw new AssertionError("ui_state_" + control + " status=" + status + " systemTrust=" + systemTrust);
    }
}
