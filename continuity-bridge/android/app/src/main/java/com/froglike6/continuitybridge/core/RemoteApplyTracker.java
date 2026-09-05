package com.froglike6.continuitybridge;

final class RemoteApplyTracker {
    private static String pending;
    private static String observationIdentity;
    private RemoteApplyTracker() { }
    static synchronized void begin(String eventId) { pending = eventId; observationIdentity = null; }
    static synchronized boolean matches(String eventId) { return eventId != null && eventId.equals(pending); }
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
        if (matches(eventId)) { pending = null; observationIdentity = null; }
    }
}
