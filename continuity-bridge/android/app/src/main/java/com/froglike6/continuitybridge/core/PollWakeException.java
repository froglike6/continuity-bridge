package com.froglike6.continuitybridge;

import java.io.IOException;

public final class PollWakeException extends IOException {
    private static final long serialVersionUID = 1L;
    public PollWakeException() { super("outbox_ready"); }
}
