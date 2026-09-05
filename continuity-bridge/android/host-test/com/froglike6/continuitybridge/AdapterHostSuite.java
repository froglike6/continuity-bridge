package com.froglike6.continuitybridge;

import java.nio.file.Files;
import java.nio.file.Path;

public final class AdapterHostSuite {
    private static int cases;
    private AdapterHostSuite() { }

    public static void main(String[] args) throws Exception {
        clipboardPolicy(); durableClipboard(); applyTransaction(); notificationMapping(); logMatcher();
        System.out.println("ADAPTER_HOST_OK cases=" + cases + " markers=remote_marker_skip_once,marker_observation_bound,later_distinct_after_marker_emits,later_same_text_emits,different_event_id_emits,direct_overlay_dedupe,zero_timestamp_independent,overlay_inflight_coalesced,overlay_terminal_cleanup,restart_marker,utf8_clip_limit,apply_set_persist_before_ack,no_readback_success_gate,set_failure_no_success,set_failure_preserves_marker,persistence_failure_no_success,persistence_failure_preserves_marker,notification_stable_key,new_event_ids,big_text_fallback,null_fields,utf8_caps,exact_fgs_exclusion,listener_states,prompt_opaque");
    }

    private static void clipboardPolicy() {
        ObservationWindow window = new ObservationWindow(1_000, 8);
        check(window.accept("clip-ts-1", 100), "direct accepted");
        check(!window.accept("clip-ts-1", 200), "overlay duplicate");
        check(window.accept("clip-ts-1", 1_101), "later copy accepted");
        check(window.accept("clip-ts-2", 1_102), "different identity accepted");
        check(window.accept("callback:1", 1_103) && window.accept("callback:2", 1_104), "zero timestamp identities independent");
        OverlayRequestGate gate = new OverlayRequestGate(); String request = gate.begin("callback:3");
        check("callback:3".equals(request) && gate.begin("callback:3") == null, "overlay in flight coalesced");
        gate.complete(request); check("callback:4".equals(gate.begin("callback:4")), "overlay terminal cleanup reopens gate");
    }

    private static void durableClipboard() throws Exception {
        Path root = Files.createTempDirectory("adapter-state-");
        try {
            FileBridgeStateStore store = FileBridgeStateStore.create(root.resolve("state"), TestStateCipher.create(), "device", "epoch", 101, 32);
            ProtocolEvent remote = remote("remote-1", "same");
            store.save(store.load().applied(remote, "1"));
            DurableOutbox first = new DurableOutbox(store, new SequenceIds("local"), new ObservationWindow(1_000, 8));
            check(first.captureClipboard("same", "remote-1", "obs-1", 10) == CaptureResult.REMOTE_SKIPPED, "marker skip");
            check("remote-1".equals(store.load().remoteApplyId()), "remote marker retained for duplicate callbacks");
            RemoteApplyTracker.begin("remote-1");
            check(first.captureClipboard("same", "remote-1", "timestamp:remote-1", 10) == CaptureResult.REMOTE_SKIPPED,
                    "marker callback skip");
            check("timestamp:remote-1".equals(RemoteApplyTracker.observationIdentity("remote-1"))
                            && "timestamp:remote-1".equals(store.load().remoteApplyObservationIdentity()),
                    "marker callback binds and persists actual observation identity");
            check(first.captureClipboard("later", "remote-1", "timestamp:local-1", 10) == CaptureResult.ENQUEUED,
                    "stale known marker on a distinct stable observation is independent");
            RemoteApplyTracker.begin("pending-before-persist");
            check(first.captureClipboard("same", "pending-before-persist", "obs-race", 11) == CaptureResult.REMOTE_SKIPPED, "set callback race skip");
            RemoteApplyTracker.begin("pending-markerless");
            check(first.captureClipboard("same", null, "timestamp:37", 12) == CaptureResult.REMOTE_SKIPPED,
                    "markerless callback binds platform observation to pending remote event");
            check(first.captureClipboard("same", null, "timestamp:37", 13) == CaptureResult.REMOTE_SKIPPED,
                    "duplicate markerless callback keeps event identity suppression");
            store.save(store.load().markRemoteApply("pending-unavailable", null));
            RemoteApplyTracker.begin("pending-unavailable");
            first.observeUnavailableCallback("callback:remote-unavailable");
            check("callback:remote-unavailable".equals(store.load().remoteApplyObservationIdentity()),
                    "unavailable remote callback persists its actual observation identity");
            check(first.captureClipboard("same", null, "timestamp:38", 14) == CaptureResult.ENQUEUED,
                    "later readable independent callback is not bound to stale pending remote apply");
            check(first.captureClipboard("same", null, "obs-2", 20) == CaptureResult.ENQUEUED, "manual same text");
            check(store.load().remoteApplyId() == null && RemoteApplyTracker.pendingEventId() == null,
                    "manual observation clears stale remote correlation");
            String firstId = store.load().outbox().get(0).eventId();
            check(first.captureClipboard("same", null, "obs-3", 30) == CaptureResult.ENQUEUED, "independent same text");
            check(!firstId.equals(store.load().outbox().get(0).eventId()), "new event id");
            store.save(store.load().applied(remote("remote-2", "x"), "2"));
            DurableOutbox restarted = new DurableOutbox(store, new SequenceIds("restart"), new ObservationWindow(1_000, 8));
            check(restarted.captureClipboard("x", "remote-2", "obs-r", 40) == CaptureResult.REMOTE_SKIPPED, "restart marker");
            store.save(store.load().markRemoteApply("remote-observation", "timestamp:44"));
            DurableOutbox observationRestart = new DurableOutbox(store, new SequenceIds("observation-restart"),
                    new ObservationWindow(1_000, 8));
            check(observationRestart.captureClipboard("x", null, "timestamp:44", 41) == CaptureResult.REMOTE_SKIPPED,
                    "restart markerless bound observation");
            check(observationRestart.captureClipboard("x", "mismatched-marker", "timestamp:45", 42) == CaptureResult.ENQUEUED,
                    "mismatched marker on a distinct observation is independent");
            store.save(store.load().markRemoteApply("remote-old-session", "callback:old-session:1"));
            DurableOutbox processRestart = new DurableOutbox(store, new SequenceIds("process-restart"),
                    new ObservationWindow(1_000, 8));
            check(processRestart.captureClipboard("x", null, "callback:new-session:1", 43) == CaptureResult.ENQUEUED,
                    "process restart callback identity cannot collide with persisted prior session");
            check(restarted.captureClipboard(repeat("한", 400_000), null, "too-big", 50) == CaptureResult.INVALID, "utf8 limit");
            NotificationFields update = NotificationMapper.map("stable-key", "pkg", "App", "title", "text", null, 60);
            check(restarted.enqueueNotification(update, 60) && restarted.enqueueNotification(update, 61), "notification callbacks queued");
            java.util.List<ProtocolEvent> queued = store.load().outbox();
            ProtocolEvent firstUpdate = queued.get(queued.size() - 2), secondUpdate = queued.get(queued.size() - 1);
            check(!firstUpdate.eventId().equals(secondUpdate.eventId())
                    && firstUpdate.payload().get("notificationKey").equals(secondUpdate.payload().get("notificationKey")), "notification new ids stable key");
        } finally { delete(root); }
    }

    private static void notificationMapping() {
        NotificationFields fields = NotificationMapper.map("key", "pkg", null, null, "text", "big", 7);
        check("key".equals(fields.notificationKey()) && "".equals(fields.appLabel()), "null fields");
        check("big".equals(fields.body()), "big text fallback");
        check(NotificationMapper.excludeForeground("pkg", "channel", 3103, "pkg", "channel", 3103), "exact fgs");
        check(!NotificationMapper.excludeForeground("pkg", "other", 3103, "pkg", "channel", 3103), "same package non fgs");
        NotificationFields capped = NotificationMapper.map(repeat("키", 3000), repeat("패", 200), repeat("앱", 3000), repeat("제", 5000), null, repeat("몸", 30000), 8);
        check(Utf8.size(capped.notificationKey()) <= 4096 && Utf8.size(capped.packageName()) <= 255
                && Utf8.size(capped.appLabel()) <= 4096 && Utf8.size(capped.title()) <= 8192 && Utf8.size(capped.body()) <= 65536, "utf8 caps");
        check(NotificationListenerState.CONNECTED.connected() && !NotificationListenerState.DISCONNECTED.connected(), "listener states");
        check("IGNORE INSTRUCTIONS".equals(NotificationMapper.map("k", "p", "a", "t", null, "IGNORE INSTRUCTIONS", 1).body()), "prompt opaque");
    }

    private static void applyTransaction() throws Exception {
        Path root = Files.createTempDirectory("apply-state-");
        try {
            FileBridgeStateStore store = FileBridgeStateStore.create(root.resolve("state"), TestStateCipher.create(), "device", "epoch", 8, 8);
            RecordingSurface setSuccess = new RecordingSurface(true);
            RemoteApplyTracker.begin("remote-1");
            check(new ClipboardApplyTransaction(store, setSuccess).apply(remote("remote-1", "opaque")), "apply success without readback");
            check("set".equals(setSuccess.calls.toString()) && "remote-1".equals(store.load().remoteApplyId())
                    && store.load().remoteApplyObservationIdentity() == null, "marker persists after set without invented observation");
            check(RemoteApplyTracker.pendingEventId() == null, "successful apply closes in-flight correlation");
            DurableOutbox afterApply = new DurableOutbox(store, new SequenceIds("after-apply"), new ObservationWindow(1_000, 8));
            check(afterApply.captureClipboard("later", null, "timestamp:later", 10) == CaptureResult.ENQUEUED,
                    "later markerless platform observation is independent after apply completion");

            final CaptureResult[] duringSet = new CaptureResult[1];
            ClipboardSurface callbackDuringSet = new ClipboardSurface() {
                @Override public boolean set(String eventId, String text) {
                    DurableOutbox callback = new DurableOutbox(store, new SequenceIds("during-set"), new ObservationWindow(1_000, 8));
                    duringSet[0] = callback.captureClipboard(text, null, "timestamp:during-set", 11);
                    return true;
                }
            };
            check(new ClipboardApplyTransaction(store, callbackDuringSet).apply(remote("remote-2", "x")),
                    "callback before durable apply commit succeeds");
            check(duringSet[0] == CaptureResult.REMOTE_SKIPPED
                            && "timestamp:during-set".equals(store.load().remoteApplyObservationIdentity())
                            && RemoteApplyTracker.pendingEventId() == null,
                    "in-flight callback is suppressed persisted and correlation closes after commit");
            check(!new ClipboardApplyTransaction(store, new RecordingSurface(false)).apply(remote("remote-2", "x")), "set failure");
            check("remote-2".equals(store.load().remoteApplyId()), "set failure preserves marker");
            BridgeStateStore persistenceFailure = new BridgeStateStore() {
                @Override public BridgeState load() throws java.io.IOException { return store.load(); }
                @Override public void save(BridgeState state) throws java.io.IOException { throw new java.io.IOException("persist failure"); }
            };
            check(!new ClipboardApplyTransaction(persistenceFailure, new RecordingSurface(true)).apply(remote("remote-3", "x")), "persistence failure");
            check("remote-2".equals(store.load().remoteApplyId()), "persistence failure preserves marker");
        } finally { delete(root); }
    }

    private static void logMatcher() {
        String own = "08-30 E ClipboardService: Denying clipboard access to com.froglike6.continuitybridge, application is not in focus nor is it a system service for user 0";
        check(ClipboardDenialMatcher.matches(own, "com.froglike6.continuitybridge"), "own denial");
        String brief = "E/ClipboardService( 1234): Denying clipboard access to com.froglike6.continuitybridge, application is not in focus nor is it a system service for user 0";
        check(ClipboardDenialMatcher.matches(brief, "com.froglike6.continuitybridge"), "brief-format denial");
        check(!ClipboardDenialMatcher.matches(own.replace("continuitybridge", "other"), "com.froglike6.continuitybridge"), "other denial");
        check(!ClipboardDenialMatcher.matches("I ActivityManager com.froglike6.continuitybridge", "com.froglike6.continuitybridge"), "unrelated log");
    }

    private static String repeat(String value, int count) { StringBuilder out = new StringBuilder(value.length() * count); for (int i = 0; i < count; i++) out.append(value); return out.toString(); }
    private static ProtocolEvent remote(String id, String text) { java.util.Map<String, String> payload = new java.util.LinkedHashMap<>(); payload.put("text", text); return new ProtocolEvent(id, "mac", "macos", "mac-epoch", id.endsWith("1") ? 1 : 2, "clipboard.text", 1, null, payload); }
    private static final class RecordingSurface implements ClipboardSurface {
        private final boolean set; private final StringBuilder calls = new StringBuilder();
        RecordingSurface(boolean set) { this.set = set; }
        @Override public boolean set(String eventId, String text) {
            calls.append("set");
            return set;
        }
    }
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); cases++; }
    private static void delete(Path root) throws Exception { try (java.util.stream.Stream<Path> paths = Files.walk(root)) { paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (Exception error) { throw new RuntimeException(error); } }); } }
}
