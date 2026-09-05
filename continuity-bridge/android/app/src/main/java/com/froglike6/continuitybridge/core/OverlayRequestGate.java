package com.froglike6.continuitybridge;

final class OverlayRequestGate {
    private String inFlight;
    synchronized String begin(String observationIdentity) {
        if (inFlight != null) return null;
        inFlight = observationIdentity; return inFlight;
    }
    synchronized void complete(String observationIdentity) {
        if (inFlight != null && inFlight.equals(observationIdentity)) inFlight = null;
    }
}
