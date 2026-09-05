package com.froglike6.continuitybridge;

import java.io.IOException;

public final class CancelledTransportException extends IOException {
    private static final long serialVersionUID = 1L;
    public CancelledTransportException() { super("transport_cancelled"); }
}
