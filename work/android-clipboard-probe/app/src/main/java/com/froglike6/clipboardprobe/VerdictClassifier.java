package com.froglike6.clipboardprobe;

public final class VerdictClassifier {
    public enum Outcome {
        CONTROL_CALLBACK_OK,
        CONTROL_NOTIFICATION_OK,
        CONTROL_DUPLICATE_IGNORED,
        CONTROL_TIMEOUT,
        BACKGROUND_CALLBACK_READ_OK,
        BACKGROUND_CALLBACK_READ_BLOCKED,
        BACKGROUND_WRONG_TOKEN,
        BACKGROUND_TIMEOUT,
        SECURITY_EXCEPTION
    }

    public enum Verdict {
        PASS,
        PARTIAL_UNUSABLE,
        FAIL,
        INCONCLUSIVE
    }

    public static final class Evidence {
        public final boolean controlCallback;
        public final boolean controlNotification;
        public final boolean appAlive;
        public final boolean probeNonFocused;
        public final boolean externalAppFocused;
        public final boolean callbackDelivered;
        public final boolean exactToken;
        public final Outcome outcome;

        public Evidence(boolean controlCallback, boolean controlNotification, boolean appAlive,
                boolean probeNonFocused, boolean externalAppFocused, boolean callbackDelivered,
                boolean exactToken, Outcome outcome) {
            this.controlCallback = controlCallback;
            this.controlNotification = controlNotification;
            this.appAlive = appAlive;
            this.probeNonFocused = probeNonFocused;
            this.externalAppFocused = externalAppFocused;
            this.callbackDelivered = callbackDelivered;
            this.exactToken = exactToken;
            this.outcome = outcome;
        }
    }

    private VerdictClassifier() {
    }

    public static Outcome classifyReadableBackgroundClip(String observedValue, long observedTimestamp,
            String expectedControlValue, long successfulControlTimestamp,
            String expectedBackgroundValue) {
        if (observedValue != null && expectedControlValue != null
                && expectedControlValue.equals(observedValue)
                && observedTimestamp > 0L && successfulControlTimestamp > 0L
                && observedTimestamp == successfulControlTimestamp) {
            return Outcome.CONTROL_DUPLICATE_IGNORED;
        }
        if (observedValue != null && expectedBackgroundValue != null
                && expectedBackgroundValue.equals(observedValue)) {
            return Outcome.BACKGROUND_CALLBACK_READ_OK;
        }
        return Outcome.BACKGROUND_WRONG_TOKEN;
    }

    public static Verdict classify(Evidence evidence) {
        if (evidence == null || evidence.outcome == null || !evidence.controlCallback
                || !evidence.controlNotification || !evidence.appAlive
                || !evidence.probeNonFocused || !evidence.externalAppFocused) {
            return Verdict.INCONCLUSIVE;
        }
        if (evidence.outcome == Outcome.BACKGROUND_CALLBACK_READ_BLOCKED
                || evidence.outcome == Outcome.SECURITY_EXCEPTION) {
            return Verdict.PARTIAL_UNUSABLE;
        }
        if (evidence.outcome == Outcome.BACKGROUND_CALLBACK_READ_OK) {
            if (evidence.callbackDelivered && evidence.exactToken) {
                return Verdict.PASS;
            }
            return Verdict.INCONCLUSIVE;
        }
        if (evidence.outcome == Outcome.BACKGROUND_TIMEOUT && !evidence.callbackDelivered) {
            return Verdict.FAIL;
        }
        return Verdict.INCONCLUSIVE;
    }
}
