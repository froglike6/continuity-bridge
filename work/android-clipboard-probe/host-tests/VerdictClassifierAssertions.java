package com.froglike6.clipboardprobe;

public final class VerdictClassifierAssertions {
    private VerdictClassifierAssertions() {
    }

    public static void main(String[] args) {
        assert VerdictClassifier.classify(base(VerdictClassifier.Outcome.BACKGROUND_CALLBACK_READ_OK,
                true, true, true)) == VerdictClassifier.Verdict.PASS;
        assert VerdictClassifier.classify(base(VerdictClassifier.Outcome.BACKGROUND_CALLBACK_READ_OK,
                false, true, true)) == VerdictClassifier.Verdict.INCONCLUSIVE;
        assert VerdictClassifier.classify(base(VerdictClassifier.Outcome.BACKGROUND_CALLBACK_READ_BLOCKED,
                true, true, true)) == VerdictClassifier.Verdict.PARTIAL_UNUSABLE;
        assert VerdictClassifier.classify(base(VerdictClassifier.Outcome.SECURITY_EXCEPTION,
                true, true, true)) == VerdictClassifier.Verdict.PARTIAL_UNUSABLE;
        assert VerdictClassifier.classify(base(VerdictClassifier.Outcome.BACKGROUND_WRONG_TOKEN,
                false, true, true)) == VerdictClassifier.Verdict.INCONCLUSIVE;
        assert VerdictClassifier.classify(base(VerdictClassifier.Outcome.BACKGROUND_TIMEOUT,
                false, true, false)) == VerdictClassifier.Verdict.FAIL;
        assert VerdictClassifier.classify(base(VerdictClassifier.Outcome.BACKGROUND_TIMEOUT,
                false, false, false)) == VerdictClassifier.Verdict.INCONCLUSIVE;
        assert VerdictClassifier.classify(base(VerdictClassifier.Outcome.BACKGROUND_TIMEOUT,
                false, true, true)) == VerdictClassifier.Verdict.INCONCLUSIVE;
        assert VerdictClassifier.classify(base(VerdictClassifier.Outcome.CONTROL_TIMEOUT,
                false, true, false)) == VerdictClassifier.Verdict.INCONCLUSIVE;
        assert VerdictClassifier.classify(new VerdictClassifier.Evidence(
                true, false, true, true, true, true, true,
                VerdictClassifier.Outcome.BACKGROUND_CALLBACK_READ_OK))
                == VerdictClassifier.Verdict.INCONCLUSIVE;
        assert VerdictClassifier.classify(new VerdictClassifier.Evidence(
                false, true, true, true, true, false, false,
                VerdictClassifier.Outcome.BACKGROUND_TIMEOUT))
                == VerdictClassifier.Verdict.INCONCLUSIVE;
        assert VerdictClassifier.classify(null) == VerdictClassifier.Verdict.INCONCLUSIVE;
        TrialLifecycle lifecycle = new TrialLifecycle();
        lifecycle.begin();
        lifecycle.listenerRegistered();
        assert lifecycle.listenerIsRegistered();
        assert lifecycle.timeoutOutcome() == VerdictClassifier.Outcome.CONTROL_TIMEOUT;
        lifecycle.armBackgroundDeadline();
        assert lifecycle.listenerIsRegistered();
        assert lifecycle.timeoutOutcome() == VerdictClassifier.Outcome.BACKGROUND_TIMEOUT;
        assert VerdictClassifier.classifyReadableBackgroundClip(
                "CONTROL_1", 1000L, "CONTROL_1", 1000L, "CLIP_PROBE_BG_1")
                == VerdictClassifier.Outcome.CONTROL_DUPLICATE_IGNORED;
        assert lifecycle.listenerIsRegistered()
                : "Ignoring the same control clip instance must leave the listener registered";
        assert lifecycle.timeoutOutcome() == VerdictClassifier.Outcome.BACKGROUND_TIMEOUT
                : "Ignoring the same control clip instance must preserve the background deadline";
        assert VerdictClassifier.classifyReadableBackgroundClip(
                "CONTROL_1", 1001L, "CONTROL_1", 1000L, "CLIP_PROBE_BG_1")
                == VerdictClassifier.Outcome.BACKGROUND_WRONG_TOKEN
                : "A genuine new CONTROL_1 clip with a different timestamp must remain wrong-token";
        assert VerdictClassifier.classifyReadableBackgroundClip(
                "CONTROL_1", 0L, "CONTROL_1", 1000L, "CLIP_PROBE_BG_1")
                == VerdictClassifier.Outcome.BACKGROUND_WRONG_TOKEN
                : "A zero observed timestamp cannot prove control-clip identity";
        assert VerdictClassifier.classifyReadableBackgroundClip(
                "CONTROL_1", 1000L, "CONTROL_1", 0L, "CLIP_PROBE_BG_1")
                == VerdictClassifier.Outcome.BACKGROUND_WRONG_TOKEN
                : "A zero stored timestamp cannot prove control-clip identity";
        assert VerdictClassifier.classifyReadableBackgroundClip(
                "CLIP_PROBE_BG_1", 1001L, "CONTROL_1", 1000L, "CLIP_PROBE_BG_1")
                == VerdictClassifier.Outcome.BACKGROUND_CALLBACK_READ_OK;
        assert VerdictClassifier.classifyReadableBackgroundClip(
                "OTHER", 1001L, "CONTROL_1", 1000L, "CLIP_PROBE_BG_1")
                == VerdictClassifier.Outcome.BACKGROUND_WRONG_TOKEN;
        lifecycle.terminalCleanup();
        assert !lifecycle.listenerIsRegistered();
        System.out.println("VerdictClassifier assertions passed");
    }

    private static VerdictClassifier.Evidence base(VerdictClassifier.Outcome outcome,
            boolean exactToken, boolean appAlive, boolean callbackDelivered) {
        return new VerdictClassifier.Evidence(
                true, true, appAlive, true, true, callbackDelivered, exactToken, outcome);
    }
}
