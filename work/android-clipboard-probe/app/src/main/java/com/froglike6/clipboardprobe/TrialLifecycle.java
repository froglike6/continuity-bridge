package com.froglike6.clipboardprobe;

public final class TrialLifecycle {
    private boolean listenerRegistered;
    private boolean backgroundArmed;

    public void begin() {
        if (listenerRegistered) {
            throw new IllegalStateException("listener must be cleaned up before a new trial");
        }
        backgroundArmed = false;
    }

    public void listenerRegistered() {
        listenerRegistered = true;
    }

    public boolean listenerIsRegistered() {
        return listenerRegistered;
    }

    public void armBackgroundDeadline() {
        if (!listenerRegistered) {
            throw new IllegalStateException("background deadline requires a registered listener");
        }
        backgroundArmed = true;
    }

    public VerdictClassifier.Outcome timeoutOutcome() {
        if (!listenerRegistered) {
            throw new IllegalStateException("timeout requires a registered listener");
        }
        if (backgroundArmed) {
            return VerdictClassifier.Outcome.BACKGROUND_TIMEOUT;
        }
        return VerdictClassifier.Outcome.CONTROL_TIMEOUT;
    }

    public void terminalCleanup() {
        listenerRegistered = false;
        backgroundArmed = false;
    }
}
