package com.froglike6.continuitybridge;

import java.io.IOException;

public final class CorruptStateException extends IOException {
    private static final long serialVersionUID = 1L;
    CorruptStateException(String message) { super(message); }
    CorruptStateException(String message, Throwable cause) { super(message, cause); }
}
