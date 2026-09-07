package com.froglike6.continuitybridge;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ClipboardConfirmationSuite {
    private ClipboardConfirmationSuite() { }

    static int run() throws Exception {
        // Given: a remote write is awaiting its first matching platform observation.
        RemoteApplyTracker.begin("receipt-1", "expected clipboard");
        try {
            check(!RemoteApplyTracker.awaitConfirmation("receipt-1", 0), "unobserved write was confirmed");
            check(!RemoteApplyTracker.confirm(null, "unrelated clipboard", "timestamp:1")
                    && !RemoteApplyTracker.awaitConfirmation("receipt-1", 0), "unrelated text confirmed a remote write");
            check(!RemoteApplyTracker.confirm("other-event", "expected clipboard", "timestamp:2"),
                    "different event marker confirmed a remote write");
            // When: a platform that strips extras exposes the exact expected text.
            check(RemoteApplyTracker.confirm(null, "expected clipboard", "timestamp:3"), "markerless expected text not confirmed");
            // Then: the receipt binds to that one actual clipboard observation.
            check(RemoteApplyTracker.awaitConfirmation("receipt-1", 0)
                    && "timestamp:3".equals(RemoteApplyTracker.observationIdentity("receipt-1")), "receipt did not bind platform observation");
            check(!RemoteApplyTracker.confirm(null, "expected clipboard", "timestamp:4"),
                    "later same-text observation replaced the first receipt");
        } finally { RemoteApplyTracker.clear("receipt-1"); }

        cancelledWait(false);
        cancelledWait(true);
        interruptedWait();
        return 9;
    }

    private static void cancelledWait(boolean replace) throws Exception {
        RemoteApplyTracker.begin("cancelled-receipt", "expected");
        CountDownLatch entered = new CountDownLatch(1);
        final boolean[] confirmed = new boolean[] {true};
        Thread waiter = new Thread(() -> {
            entered.countDown();
            confirmed[0] = RemoteApplyTracker.awaitConfirmation("cancelled-receipt", 5_000);
        }, "clipboard-confirmation-cancel");
        waiter.start();
        if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("receipt waiter did not start");
        // When: Stop or a new apply invalidates the pending receipt.
        if (replace) RemoteApplyTracker.begin("next-receipt", "new expected");
        else RemoteApplyTracker.clear("cancelled-receipt");
        waiter.join(2_000);
        try { check(!waiter.isAlive() && !confirmed[0], "invalidated receipt did not unblock as unconfirmed"); }
        finally { waiter.interrupt(); RemoteApplyTracker.clear("next-receipt"); }
    }

    private static void interruptedWait() throws Exception {
        RemoteApplyTracker.begin("interrupted-receipt", "expected");
        final boolean[] interrupted = new boolean[1];
        Thread waiter = new Thread(() -> {
            // When: the service worker is interrupted before waiting for the receipt.
            Thread.currentThread().interrupt();
            interrupted[0] = !RemoteApplyTracker.awaitConfirmation("interrupted-receipt", 5_000)
                    && Thread.currentThread().isInterrupted();
        }, "clipboard-confirmation-interrupt");
        waiter.start(); waiter.join(2_000);
        try { check(!waiter.isAlive() && interrupted[0], "receipt wait swallowed service cancellation"); }
        finally { waiter.interrupt(); RemoteApplyTracker.clear("interrupted-receipt"); }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
