package com.froglike6.continuitybridge;

import java.net.SocketTimeoutException;
import java.util.Random;

public final class RetryPolicy {
    public enum Decision { SUCCESS, RETRY, AUTH_TERMINAL, TLS_TERMINAL, CLIENT_TERMINAL }
    private final long baseMs;
    private final long maximumMs;
    private final Random random;

    public RetryPolicy(long baseMs, long maximumMs, Random random) {
        if (baseMs < 1 || maximumMs < baseMs) throw new IllegalArgumentException("invalid_backoff");
        this.baseMs = baseMs; this.maximumMs = maximumMs; this.random = random;
    }

    public Decision classify(int status, Exception error) {
        if (error instanceof javax.net.ssl.SSLException) return Decision.TLS_TERMINAL;
        if (error instanceof SocketTimeoutException || error instanceof java.io.IOException) return Decision.RETRY;
        if (status >= 200 && status < 300) return Decision.SUCCESS;
        if ((status >= 300 && status < 400) || status == 401 || status == 403) return Decision.AUTH_TERMINAL;
        if (status == 408 || status == 429 || status >= 500) return Decision.RETRY;
        return Decision.CLIENT_TERMINAL;
    }

    public long delayMs(int attempt) {
        int shift = Math.max(0, Math.min(attempt, 30));
        long exponential = shift >= 63 || baseMs > (Long.MAX_VALUE >> shift) ? maximumMs : baseMs << shift;
        long capped = Math.min(maximumMs, exponential);
        long lower = capped / 2;
        return lower + (long) (random.nextDouble() * (capped - lower + 1));
    }
}
