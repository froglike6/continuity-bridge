package com.froglike6.continuitybridge;

enum NotificationListenerState {
    CONNECTED(true), DISCONNECTED(false);
    private final boolean connected;
    NotificationListenerState(boolean connected) { this.connected = connected; }
    boolean connected() { return connected; }
}
