package com.froglike6.continuitybridge;

final class UiStatePolicySuite {
    private UiStatePolicySuite() { }

    static int run() {
        int cases = 0;
        cases += persistedPermissionRequiredWithGrantedNotificationPermissionReconcilesToStopped();
        for (ConnectionStatus status : ConnectionStatus.values()) {
            boolean active = status == ConnectionStatus.CONNECTING || status == ConnectionStatus.CONNECTED || status == ConnectionStatus.RETRY;
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

    private static int persistedPermissionRequiredWithGrantedNotificationPermissionReconcilesToStopped() {
        check(UiStatePolicy.reconcilePermissionStatus(ConnectionStatus.PERMISSION_REQUIRED, true) == ConnectionStatus.STOPPED,
                ConnectionStatus.PERMISSION_REQUIRED, true, "persisted_permission_required_grant_reconciles_to_stopped");
        check(UiStatePolicy.reconcilePermissionStatus(ConnectionStatus.PERMISSION_REQUIRED, false) == ConnectionStatus.PERMISSION_REQUIRED,
                ConnectionStatus.PERMISSION_REQUIRED, false, "denied_permission_remains_required");
        int cases = 2;
        for (ConnectionStatus status : ConnectionStatus.values()) {
            if (status != ConnectionStatus.PERMISSION_REQUIRED) {
                check(UiStatePolicy.reconcilePermissionStatus(status, true) == status, status, true, "granted_permission_does_not_rewrite_status");
                cases++;
            }
        }
        return cases;
    }

    private static void check(boolean condition, ConnectionStatus status, boolean systemTrust, String control) {
        if (!condition) throw new AssertionError("ui_state_" + control + " status=" + status + " systemTrust=" + systemTrust);
    }
}
