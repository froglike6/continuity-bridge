package com.froglike6.continuitybridge;

public final class UiStatePolicy {
    private UiStatePolicy() { }

    public static Decision forStatus(ConnectionStatus status, boolean systemTrust) {
        boolean active = requiresServiceRun(status);
        return new Decision(!active, active, !active, !active && !systemTrust);
    }

    public static ConnectionStatus afterPermissionResult(ConnectionStatus status, boolean notificationRequest, boolean granted) {
        return status == ConnectionStatus.PERMISSION_REQUIRED && notificationRequest && granted ? ConnectionStatus.STOPPED : status;
    }

    public static ConnectionStatus reconcileServiceStatus(ConnectionStatus status, boolean serviceRunActive) {
        return requiresServiceRun(status) && !serviceRunActive ? ConnectionStatus.STOPPED : status;
    }

    private static boolean requiresServiceRun(ConnectionStatus status) {
        return status == ConnectionStatus.CONNECTING || status == ConnectionStatus.CONNECTED || status == ConnectionStatus.RETRY;
    }

    public static final class Decision {
        private final boolean startEnabled;
        private final boolean stopEnabled;
        private final boolean configurationEnabled;
        private final boolean pinEnabled;

        private Decision(boolean startEnabled, boolean stopEnabled, boolean configurationEnabled, boolean pinEnabled) {
            this.startEnabled = startEnabled;
            this.stopEnabled = stopEnabled;
            this.configurationEnabled = configurationEnabled;
            this.pinEnabled = pinEnabled;
        }

        public boolean startEnabled() { return startEnabled; }
        public boolean stopEnabled() { return stopEnabled; }
        public boolean configurationEnabled() { return configurationEnabled; }
        public boolean pinEnabled() { return pinEnabled; }
    }
}
