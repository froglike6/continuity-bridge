package com.froglike6.continuitybridge;

public final class UiStatePolicy {
    private UiStatePolicy() { }

    public static Decision forStatus(ConnectionStatus status, boolean systemTrust) {
        boolean active = status == ConnectionStatus.CONNECTING || status == ConnectionStatus.CONNECTED || status == ConnectionStatus.RETRY;
        return new Decision(!active, active, !active, !active && !systemTrust);
    }

    public static ConnectionStatus reconcilePermissionStatus(ConnectionStatus status, boolean notificationPermissionGranted) {
        return status == ConnectionStatus.PERMISSION_REQUIRED && notificationPermissionGranted ? ConnectionStatus.STOPPED : status;
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
