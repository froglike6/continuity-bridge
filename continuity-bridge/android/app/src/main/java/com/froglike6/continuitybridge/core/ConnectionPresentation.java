package com.froglike6.continuitybridge;

public final class ConnectionPresentation {
    private ConnectionPresentation() { }

    public static boolean showConnectingBeforeAttempt(BridgeEngine.Status previous) {
        return previous != BridgeEngine.Status.CONNECTED;
    }
}
