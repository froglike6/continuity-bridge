package com.froglike6.continuitybridge;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class RemoteApplyTracker {
    private static String pending;
    private static String expectedText;
    private static String expectedKind;
    private static String observationIdentity;
    private static CountDownLatch observation;
    private static boolean confirmed;
    private RemoteApplyTracker() { }
    static synchronized void begin(String eventId) { begin(eventId, null); }
    static synchronized void begin(String eventId, String text) {
        beginValue(eventId, "clipboard.text", text);
    }
    static synchronized void beginContent(String eventId, ClipboardContent content) {
        beginValue(eventId, content.kind(), content.fingerprint());
    }
    private static void beginValue(String eventId, String kind, String value) {
        if (observation != null) observation.countDown();
        pending = eventId; expectedText = value; expectedKind = kind; observationIdentity = null;
        observation = new CountDownLatch(1); confirmed = false;
    }
    static synchronized boolean matches(String eventId) { return eventId != null && eventId.equals(pending); }
    static synchronized boolean matchesText(String text) {
        return expectedText == null || ("clipboard.text".equals(expectedKind) && expectedText.equals(text));
    }
    static synchronized boolean matchesContent(ClipboardContent content) {
        return expectedText == null || (content.kind().equals(expectedKind) && expectedText.equals(content.fingerprint()));
    }
    static synchronized boolean confirm(String marker, String text, String identity) {
        return confirmValue(marker, "clipboard.text", text, identity);
    }
    static synchronized boolean confirmContent(String marker, ClipboardContent content, String identity) {
        return confirmValue(marker, content.kind(), content.fingerprint(), identity);
    }
    private static boolean confirmValue(String marker, String kind, String value, String identity) {
        if (pending == null || expectedText == null || !kind.equals(expectedKind) || !expectedText.equals(value) || (marker != null && !matches(marker))
                || identity == null || identity.isEmpty()) return false;
        if (confirmed) return identity.equals(observationIdentity);
        observationIdentity = identity; confirmed = true; observation.countDown(); return true;
    }

    static synchronized Observation observe(String marker, String identity) {
        boolean eligible = pending != null && expectedText != null && (marker == null || matches(marker))
                && (!confirmed || identity.equals(observationIdentity));
        return new Observation(pending, expectedKind, expectedText, eligible);
    }

    static final class Observation {
        final String eventId;
        private final String kind;
        private final String fingerprint;
        private final boolean eligible;
        Observation(String eventId, String kind, String fingerprint, boolean eligible) {
            this.eventId = eventId; this.kind = kind; this.fingerprint = fingerprint; this.eligible = eligible;
        }
        boolean matches(ClipboardContent content) {
            return eligible && content.kind().equals(kind) && content.fingerprint().equals(fingerprint);
        }
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
            observation.countDown(); pending = null; expectedText = null; expectedKind = null; observationIdentity = null;
            observation = null; confirmed = false;
        }
    }
}
