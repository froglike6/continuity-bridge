package com.froglike6.continuitybridge;

import java.security.GeneralSecurityException;

public interface StateCipher {
    byte[] seal(byte[] plaintext) throws GeneralSecurityException;
    byte[] open(byte[] envelope) throws GeneralSecurityException;
}
