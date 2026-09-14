package com.froglike6.continuitybridge;

public final class AccessAuthenticationException extends Exception {
    private static final long serialVersionUID = 1L;
    public AccessAuthenticationException() { super("access_credential_required"); }
}
