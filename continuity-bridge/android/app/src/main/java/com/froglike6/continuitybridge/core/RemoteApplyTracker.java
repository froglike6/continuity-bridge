package com.froglike6.continuitybridge;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class RemoteApplyTracker {
    private static String pending;
    private static String expectedText;
    private static String observationIdentity;
    private static CountDownLatch observation;
    private static boolean confirmed;
    private RemoteApplyTracker() { }
    static synchronized void begin(String eventId) { begin(eventId, null); }
    static synchronized void begin(String eventId, String text) {
        if (observation != null) observation.countDown();
        pending = eventId; expectedText = text; observationIdentity = null;
        observation = new CountDownLatch(1); confirmed = false;
    }
    static synchronized boolean matches(String eventId) { return eventId != null && eventId.equals(pending); }
    static synchronized boolean matchesText(String text) { return expectedText == null || expectedText.equals(text); }
    static synchronized boolean confirm(String marker, String text, String identity) {
        if (pending == null || expectedText == null || !matchesText(text) || (marker != null && !matches(marker))
                || identity == null || identity.isEmpty()) return false;
        if (confirmed) return identity.equals(observationIdentity);
        observationIdentity = identity; confirmed = true; observation.countDown(); return true;
    }
    static boolean awaitConfirmation(String eventId, long timeoutMs) {
        CountDownLatch receipt;
        synchronized (RemoteApplyTracker.class) {
            if (!matches(eventId)) return false;
            receipt = observation;
        }
        try { if (!receipt.await(timeoutMs, TimeUnit.MILLISECONDS)) return false; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
        synchronized (RemoteApplyTracker.class) { return matches(eventId) && confirmed; }
    }
    static synchronized boolean bindObservation(String eventId, String identity) {
        if (!matches(eventId) || identity == null || identity.isEmpty()) return false;
        if (observationIdentity == null) observationIdentity = identity;
        return observationIdentity.equals(identity);
    }
    static synchronized boolean matchesObservation(String identity) {
        return bindObservation(pending, identity);
    }
    static synchronized String observationIdentity(String eventId) {
        return matches(eventId) ? observationIdentity : null;
    }
    static synchronized String pendingEventId() { return pending; }
    static synchronized void clear(String eventId) {
        if (matches(eventId)) {
            observation.countDown(); pending = null; expectedText = null; observationIdentity = null;
            observation = null; confirmed = false;
        }
    }
}
