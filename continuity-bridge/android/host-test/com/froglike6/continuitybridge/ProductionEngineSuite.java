package com.froglike6.continuitybridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ProductionEngineSuite {
    private static final String SERVER_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SERVER_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private ProductionEngineSuite() { }

    static int run(Path fixtures) throws Exception {
        int count = 0;
        count += malformedSuccess(fixtures);
        count += cursorApplyAck(fixtures);
        count += fullEpochMetadataWithholdsApplyAndAck(fixtures);
        count += applyWithholdsAck(fixtures);
        count += unconfirmedClipboardWithholdsAck(fixtures);
        count += restartAroundAck(fixtures);
        count += serverEpochReset(fixtures);
        count += publishRetry(fixtures);
        count += producerDuringPublish(fixtures);
        count += producerBetweenPublishLoadAndSave(fixtures);
        count += producerDuringInitialEpoch(fixtures);
        count += corruptToken(fixtures);
        count += productionClassification(fixtures);
        count += stopLongPoll(fixtures);
        count += stopDuringApply(fixtures);
        count += interruptedPersistence(fixtures);
        count += responseBoundaries(fixtures);
        count += connectionPresentation();
        count += serviceRunOwnership();
        count += repeatedServiceRunRace();
        System.out.println("PRODUCTION_ENGINE_OK cases=" + count + " markers=malformed_2xx,cursor_replay,server_epoch_reset,persist_before_ack,ack_after_apply,apply_failure_withholds_ack,epoch_metadata_full_withholds_apply_ack,restart_before_ack,restart_after_ack,outbox_idempotent,producer_during_publish_preserved,producer_between_publish_load_save_preserved,producer_during_initial_epoch_preserved,401_terminal,403_terminal_Http403,timeout_retry,corrupt_token_terminal,corrupt_state_terminal,stop_long_poll,persistence_interruption_recovery,response_bounds,expires_roundtrip,service_run_ownership,service_run_race_100");
        return count;
    }

    private static int connectionPresentation() {
        check(!ConnectionPresentation.showConnectingBeforeAttempt(BridgeEngine.Status.CONNECTED),
                "connected status overwritten before next long poll");
        check(ConnectionPresentation.showConnectingBeforeAttempt(BridgeEngine.Status.RETRY),
                "retry did not return to connecting state");
        return 2;
    }

    private static int serviceRunOwnership() {
        ServiceRunCoordinator coordinator = new ServiceRunCoordinator();
        ServiceRunCoordinator.Run first = coordinator.start();
        check(first != null && coordinator.owns(first), "first service run not owned");
        check(coordinator.start() == null, "concurrent service run accepted");
        check(coordinator.finish(first), "terminal service run did not release ownership");
        ServiceRunCoordinator.Run second = coordinator.start();
        check(second != null && second != first && coordinator.owns(second), "later Start did not own a new run");
        check(!coordinator.finish(first), "stale completion cleared newer run");
        check(coordinator.owns(second), "newer run lost after stale completion");
        check(!coordinator.cancel(first), "stale destroy cleared newer run");
        check(coordinator.owns(second), "newer run lost after stale destroy");
        final int[] statusWrites = new int[] {0};
        check(!coordinator.publish(first, new Runnable() { @Override public void run() { statusWrites[0] = 1; } }),
                "stale run published status over newer run");
        check(statusWrites[0] == 0, "stale status callback executed");
        check(coordinator.publish(second, new Runnable() { @Override public void run() { statusWrites[0] = 2; } }) && statusWrites[0] == 2,
                "current run could not publish status");
        check(coordinator.cancel(second) && !coordinator.owns(second), "current destroy did not release run");
        return 12;
    }

    private static int repeatedServiceRunRace() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            ServiceRunCoordinator coordinator = new ServiceRunCoordinator();
            ServiceRunCoordinator.Run first = coordinator.start();
            CountDownLatch finished = new CountDownLatch(1);
            final ServiceRunCoordinator.Run[] second = new ServiceRunCoordinator.Run[1];
            Thread completion = new Thread(() -> { coordinator.finish(first); finished.countDown(); }, "terminal-completion");
            Thread restart = new Thread(() -> {
                try { finished.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                second[0] = coordinator.start();
            }, "same-service-restart");
            completion.start(); restart.start(); completion.join(2000); restart.join(2000);
            check(!completion.isAlive() && !restart.isAlive(), "service run race threads hung");
            check(second[0] != null && coordinator.owns(second[0]), "rapid terminal restart lost new run");
            check(!coordinator.finish(first) && !coordinator.cancel(first), "stale first run cleared rapid restart");
            check(coordinator.cancel(second[0]), "rapid restart cleanup failed");
        }
        return 4;
    }

    private static int malformedSuccess(Path fixtures) throws Exception {
        Harness harness = Harness.create(fixtures, eventFetch("not-json"));
        BridgeEngine.Result result = harness.engine.step(harness.lease);
        check(result.status() == BridgeEngine.Status.PROTOCOL_FAILURE, "malformed 2xx must not connect");
        check("0".equals(harness.store.load().cursor()), "malformed body changed cursor");
        Harness unexpected = Harness.create(fixtures, eventFetch(emptyFetch("0"))); unexpected.transport.poll.clear(); unexpected.transport.poll.add(new TransportResponse(202, emptyFetch("0")));
        check(unexpected.engine.step(unexpected.lease).status() == BridgeEngine.Status.PROTOCOL_FAILURE, "unexpected 2xx connected");
        try { RelayProtocol.fetch(emptyFetch("0").replace(SERVER_A, "server-a"), "0"); throw new AssertionError("invalid server epoch accepted"); } catch (IllegalArgumentException expected) { }
        return 4;
    }

    private static int cursorApplyAck(Path fixtures) throws Exception {
        ProtocolEvent event = EventCodec.decode(fixtures.resolve("macos-clipboard.json"));
        Harness harness = Harness.create(fixtures, eventFetch(fetch("0", "7", "7", event)));
        harness.transport.ack.add(new TransportResponse(200, ackBody(event.eventId())));
        harness.applier.succeed = true;
        BridgeEngine.Result result = harness.engine.step(harness.lease);
        BridgeState persisted = harness.store.load();
        check(result.status() == BridgeEngine.Status.CONNECTED, "valid fetch not connected");
        check(harness.transport.pollCursors.equals(Collections.singletonList("0")), "wrong persisted cursor used");
        check(harness.applier.applied.equals(Collections.singletonList(event.eventId())), "event not applied");
        check(harness.transport.acked.equals(Collections.singletonList(event.eventId())), "ack not emitted after apply");
        check("7".equals(persisted.cursor()) && persisted.pendingAcks().isEmpty(), "cursor/ack state not durable");
        return 5;
    }

    private static int fullEpochMetadataWithholdsApplyAndAck(Path fixtures) throws Exception {
        ProtocolEvent overflow = new ProtocolEvent("engine-overflow", "device-macos", "macos", "engine-epoch-64", 1,
                "clipboard.text", 1, null, Collections.singletonMap("text", "opaque"));
        Harness harness = Harness.create(fixtures, eventFetch(fetch("0", "1", "1", overflow)));
        BridgeState state = harness.store.load();
        for (int index = 0; index < BridgeState.MAX_REPLAY_ORIGIN_KEYS; index++) {
            ProtocolEvent item = new ProtocolEvent("engine-bounded-" + index, "device-macos", "macos", "engine-epoch-" + index, 1,
                    "clipboard.text", 1, null, Collections.singletonMap("text", "opaque"));
            state = state.applied(item, "0").acknowledged(Collections.singleton(item.eventId())).clearRemoteApply(item.eventId());
        }
        harness.store.save(state); harness.applier.succeed = true;
        BridgeEngine.Result result = harness.engine.step(harness.lease);
        BridgeState persisted = harness.store.load();
        check(result.status() == BridgeEngine.Status.PROTOCOL_FAILURE, "full epoch metadata did not fail closed");
        check(harness.applier.applied.isEmpty() && harness.transport.acked.isEmpty(), "full epoch metadata applied or acked overflow");
        check(persisted.highWater().size() == BridgeState.MAX_REPLAY_ORIGIN_KEYS && "0".equals(persisted.cursor()), "full epoch metadata mutated state");
        return 3;
    }

    private static int applyWithholdsAck(Path fixtures) throws Exception {
        ProtocolEvent event = EventCodec.decode(fixtures.resolve("macos-clipboard.json"));
        Harness harness = Harness.create(fixtures, eventFetch(fetch("0", "2", "2", event)));
        BridgeEngine.Result result = harness.engine.step(harness.lease);
        check(result.status() == BridgeEngine.Status.PERMISSION_REQUIRED, "apply failure did not surface permission");
        check(harness.transport.acked.isEmpty() && "0".equals(harness.store.load().cursor()), "failed apply acked or advanced cursor");
        return 2;
    }

    private static int restartAroundAck(Path fixtures) throws Exception {
        ProtocolEvent event = EventCodec.decode(fixtures.resolve("macos-clipboard.json"));
        Path directory = Files.createTempDirectory("engine-restart-");
        try {
            FileBridgeStateStore store = FileBridgeStateStore.create(directory.resolve("state.db"), TestStateCipher.create(), "android-device", "epoch", 8, 8);
            LoopbackTransport first = new LoopbackTransport(); first.poll.add(new TransportResponse(200, fetch("0", "4", "4", event))); first.ack.add(new TransportResponse(503, "{}"));
            RecordingApplier applier = new RecordingApplier(); applier.succeed = true;
            ConnectionOwner owner = new ConnectionOwner(); BridgeEngine engine = new BridgeEngine(store, first, new FixedToken(), applier, owner);
            check(engine.step(owner.start()).status() == BridgeEngine.Status.RETRY, "ack 5xx not retryable");
            check(store.load().pendingAcks().contains(event.eventId()) && "4".equals(store.load().cursor()), "apply not persisted before ack");
            LoopbackTransport second = new LoopbackTransport(); second.ack.add(new TransportResponse(200, ackBody(event.eventId()))); second.poll.add(new TransportResponse(200, emptyFetch("4")));
            ConnectionOwner nextOwner = new ConnectionOwner(); BridgeEngine restarted = new BridgeEngine(store, second, new FixedToken(), applier, nextOwner);
            check(restarted.step(nextOwner.start()).status() == BridgeEngine.Status.CONNECTED, "restart ack recovery failed");
            check(second.acked.equals(Collections.singletonList(event.eventId())) && second.pollCursors.equals(Collections.singletonList("4")), "pending ack/cursor not resumed");
            check(store.load().pendingAcks().isEmpty() && applier.applied.size() == 1, "restart re-applied or retained ack");
            return 5;
        } finally { deleteTree(directory); }
    }

    private static int unconfirmedClipboardWithholdsAck(Path fixtures) throws Exception {
        ProtocolEvent event = EventCodec.decode(fixtures.resolve("macos-clipboard.json"));
        Harness harness = Harness.create(fixtures, eventFetch(fetch("0", "2", "2", event)));
        ClipboardSurface unavailableReadback = new ClipboardSurface() {
            @Override public boolean set(String eventId, String text) { return true; }
            @Override public boolean confirm(String eventId, String text) { return false; }
        };
        ClipboardApplyTransaction transaction = new ClipboardApplyTransaction(harness.store, unavailableReadback);
        BridgeEngine engine = new BridgeEngine(harness.store, harness.transport, new FixedToken(), new EventApplier() {
            @Override public boolean apply(ProtocolEvent incoming) { return transaction.apply(incoming); }
        }, harness.owner);
        // Given: the platform write returns normally but its resulting clipboard cannot be verified.
        // When: the production engine delivers the relay event to the real apply transaction.
        BridgeEngine.Result result = engine.step(harness.lease);
        BridgeState state = harness.store.load();
        // Then: retain only the echo-suppression intent, never a completed application or ACK.
        check(result.status() == BridgeEngine.Status.PERMISSION_REQUIRED && harness.transport.acked.isEmpty()
                        && state.appliedIds().isEmpty() && state.pendingAcks().isEmpty() && "0".equals(state.cursor())
                        && event.eventId().equals(state.remoteApplyId()),
                "unverified clipboard application advanced delivery or lost durable remote intent");
        return 1;
    }

    private static int serverEpochReset(Path fixtures) throws Exception {
        Harness harness = Harness.create(fixtures, eventFetch(emptyFetch("0", "5", SERVER_A)));
        check(harness.engine.step(harness.lease).status() == BridgeEngine.Status.CONNECTED, "initial server epoch failed");
        harness.transport.poll.add(new TransportResponse(200, emptyFetch("5", "0", SERVER_B)));
        check(harness.engine.step(harness.lease).status() == BridgeEngine.Status.RETRY, "server epoch mismatch not retried");
        BridgeState state = harness.store.load();
        check("0".equals(state.cursor()) && SERVER_B.equals(state.serverEpoch()), "server epoch did not reset cursor");
        return 3;
    }

    private static int publishRetry(Path fixtures) throws Exception {
        ProtocolEvent event = EventCodec.decode(fixtures.resolve("android-clipboard.json"));
        Harness harness = Harness.create(fixtures, eventFetch(emptyFetch("0")));
        harness.store.save(harness.store.load().enqueue(event));
        harness.transport.publish.add(new TransportResponse(503, "{}"));
        check(harness.engine.step(harness.lease).status() == BridgeEngine.Status.RETRY, "publish 5xx not retryable");
        harness.transport.publish.add(new TransportResponse(201, publishBody(event.eventId())));
        harness.transport.poll.add(new TransportResponse(200, emptyFetch("0")));
        check(harness.engine.step(harness.lease).status() == BridgeEngine.Status.CONNECTED, "publish retry failed");
        check(harness.transport.published.size() == 2 && harness.transport.published.get(0).equals(harness.transport.published.get(1)), "retry changed logical event");
        check(harness.store.load().outbox().isEmpty(), "accepted publish retained outbox");
        return 4;
    }

    private static int corruptToken(Path fixtures) throws Exception {
        Harness harness = Harness.create(fixtures, eventFetch(emptyFetch("0")));
        BridgeEngine engine = new BridgeEngine(harness.store, harness.transport, new TokenProvider() {
            @Override public String load() throws SecureStoreException { throw new SecureStoreException("corrupt envelope"); }
        }, harness.applier, harness.owner);
        check(engine.step(harness.lease).status() == BridgeEngine.Status.SECURITY_FAILURE, "corrupt token retried");
        check(harness.transport.pollCursors.isEmpty(), "transport used after corrupt token");
        return 2;
    }

    private static int producerDuringPublish(Path fixtures) throws Exception {
        ProtocolEvent event = EventCodec.decode(fixtures.resolve("android-clipboard.json"));
        Harness harness = Harness.create(fixtures, eventFetch(emptyFetch("0"))); harness.store.save(harness.store.load().enqueue(event));
        harness.transport.publish.add(new TransportResponse(201, publishBody(event.eventId())));
        harness.transport.onPublish = new Runnable() { @Override public void run() {
            DurableOutbox producer = new DurableOutbox(harness.store, new SequenceIds("note"), new ObservationWindow(1000, 4));
            check(producer.enqueueNotification(NotificationMapper.map("key", "pkg", "App", "title", "body", null, 2), 2), "concurrent producer failed");
        }};
        check(harness.engine.step(harness.lease).status() == BridgeEngine.Status.CONNECTED, "producer publish step failed");
        check(harness.store.load().outbox().size() == 1 && "android.notification".equals(harness.store.load().outbox().get(0).kind()), "producer event overwritten");
        return 2;
    }

    private static int producerDuringInitialEpoch(Path fixtures) throws Exception {
        Harness harness = Harness.create(fixtures, eventFetch(emptyFetch("0")));
        harness.transport.onPoll = new Runnable() { @Override public void run() {
            DurableOutbox producer = new DurableOutbox(harness.store, new SequenceIds("initial-epoch"), new ObservationWindow(1000, 4));
            check(producer.enqueueNotification(NotificationMapper.map("key", "pkg", "App", "title", "body", null, 2), 2), "initial epoch producer failed");
        }};
        check(harness.engine.step(harness.lease).status() == BridgeEngine.Status.CONNECTED, "initial epoch producer step failed");
        BridgeState state = harness.store.load();
        check(state.outbox().size() == 1 && state.nextSequence() == 2,
                "initial epoch persistence overwrote producer state");
        return 2;
    }

    private static int producerBetweenPublishLoadAndSave(Path fixtures) throws Exception {
        Path directory = Files.createTempDirectory("publish-interleave-");
        try {
            ProtocolEvent event = EventCodec.decode(fixtures.resolve("android-clipboard.json"));
            FileBridgeStateStore backing = FileBridgeStateStore.create(directory.resolve("state.db"), TestStateCipher.create(), event.deviceId(), event.epoch(), 8, 8);
            backing.save(backing.load().enqueue(event));
            InterleavingStore store = new InterleavingStore(backing);
            LoopbackTransport transport = new LoopbackTransport();
            transport.publish.add(new TransportResponse(201, publishBody(event.eventId())));
            transport.poll.add(new TransportResponse(200, emptyFetch("0")));
            transport.onPublish = new Runnable() { @Override public void run() {
                store.arm(new Runnable() { @Override public void run() {
                    DurableOutbox producer = new DurableOutbox(store, new SequenceIds("post-publish"), new ObservationWindow(1000, 4));
                    check(producer.enqueueNotification(NotificationMapper.map("key", "pkg", "App", "title", "body", null, 2), 2), "post-publish producer failed");
                }});
            }};
            ConnectionOwner owner = new ConnectionOwner();
            BridgeEngine engine = new BridgeEngine(store, transport, new FixedToken(), new RecordingApplier(), owner);
            check(engine.step(owner.start()).status() == BridgeEngine.Status.CONNECTED, "post-publish interleaving step failed");
            store.awaitProducer();
            BridgeState state = store.load();
            check(state.outbox().size() == 1 && state.nextSequence() == 3,
                    "publish completion overwrote producer state");
            return 2;
        } finally { deleteTree(directory); }
    }

    private static int productionClassification(Path fixtures) throws Exception {
        Harness unauthorized = Harness.create(fixtures, eventFetch("{}")); unauthorized.transport.poll.clear(); unauthorized.transport.poll.add(new TransportResponse(401, "{}"));
        check(unauthorized.engine.step(unauthorized.lease).status() == BridgeEngine.Status.AUTH_FAILURE, "401 not terminal auth");
        Harness forbidden = Harness.create(fixtures, eventFetch("{}")); forbidden.transport.poll.clear(); forbidden.transport.poll.add(new TransportResponse(403, "{}"));
        BridgeEngine.Result forbiddenResult = forbidden.engine.step(forbidden.lease);
        check(forbiddenResult.status() == BridgeEngine.Status.AUTH_FAILURE && "Http403".equals(forbiddenResult.errorClass()), "403 not terminal auth");
        Harness timeout = Harness.create(fixtures, eventFetch("{}")); timeout.transport.pollError = new java.net.SocketTimeoutException("timeout");
        check(timeout.engine.step(timeout.lease).status() == BridgeEngine.Status.RETRY, "timeout not retryable");
        Path directory = Files.createTempDirectory("corrupt-engine-state-");
        try {
            Path path = directory.resolve("state.db"); Files.write(path, new byte[] {9, 8, 7});
            FileBridgeStateStore store = FileBridgeStateStore.create(path, TestStateCipher.create(), "device", "epoch", 4, 4); LoopbackTransport transport = new LoopbackTransport();
            ConnectionOwner owner = new ConnectionOwner(); BridgeEngine engine = new BridgeEngine(store, transport, new FixedToken(), new RecordingApplier(), owner);
            check(engine.step(owner.start()).status() == BridgeEngine.Status.SECURITY_FAILURE, "corrupt state retried");
            return 4;
        } finally { deleteTree(directory); }
    }

    private static int stopLongPoll(Path fixtures) throws Exception {
        Harness harness = Harness.create(fixtures, eventFetch(emptyFetch("0"))); harness.transport.blockPoll = true;
        final BridgeEngine.Result[] result = new BridgeEngine.Result[1];
        Thread thread = new Thread(() -> result[0] = harness.engine.step(harness.lease), "engine-stop-test"); thread.start();
        check(harness.transport.entered.await(2, TimeUnit.SECONDS), "poll did not start"); harness.engine.cancel(harness.lease);
        thread.join(2000); check(!thread.isAlive() && result[0].status() == BridgeEngine.Status.STOPPED, "cancel did not stop poll");
        check(harness.transport.cancelled, "transport not disconnected");
        return 3;
    }

    private static int interruptedPersistence(Path fixtures) throws Exception {
        Path directory = Files.createTempDirectory("state-interrupt-"); Path path = directory.resolve("state.db");
        try {
            ProtocolEvent outbound = EventCodec.decode(fixtures.resolve("android-clipboard.json"));
            StateCipher cipher = TestStateCipher.create();
            AtomicStateFile normal = new AtomicStateFile(path, cipher); BridgeState old = BridgeState.fresh(outbound.deviceId(), outbound.epoch(), 4, 4); normal.save(old);
            AtomicStateFile interrupted = new AtomicStateFile(path, cipher, AtomicStateFile.Fault.BEFORE_REPLACE);
            try { interrupted.save(old.enqueue(outbound)); throw new AssertionError("fault did not interrupt"); }
            catch (IOException expected) { check(normal.load().outbox().isEmpty(), "interruption lost old generation"); }
            Files.write(path, new byte[] {1, 2, 3}); check(normal.load().outbox().isEmpty(), "backup recovery failed");
            AtomicStateFile recoveryInterrupted = new AtomicStateFile(path, cipher, AtomicStateFile.Fault.BEFORE_REPLACE);
            check(recoveryInterrupted.load().outbox().isEmpty(), "second backup recovery failed");
            try { recoveryInterrupted.save(old.enqueue(outbound)); throw new AssertionError("recovery fault did not interrupt"); }
            catch (IOException expected) { check(new AtomicStateFile(path, cipher).load().outbox().isEmpty(), "recovered backup was replaced by corrupt primary"); }
            return 3;
        } finally { deleteTree(directory); }
    }

    private static int stopDuringApply(Path fixtures) throws Exception {
        ProtocolEvent event = EventCodec.decode(fixtures.resolve("macos-clipboard.json"));
        Harness harness = Harness.create(fixtures, eventFetch(fetch("0", "3", "3", event)));
        // Given: Stop occurs while the platform is confirming an already completed application.
        EventApplier stopAfterApply = new EventApplier() {
            @Override public boolean apply(ProtocolEvent incoming) {
                harness.owner.cancel(harness.lease);
                return true;
            }
        };
        BridgeEngine engine = new BridgeEngine(harness.store, harness.transport, new FixedToken(), stopAfterApply, harness.owner);
        // When: that application completes after the lease was cancelled.
        BridgeEngine.Result result = engine.step(harness.lease);
        // Then: keep the completed application durable, without making another network request.
        check(result.status() == BridgeEngine.Status.STOPPED && harness.transport.acked.isEmpty()
                        && harness.store.load().pendingAcks().contains(event.eventId()),
                "cancelled apply issued ACK instead of deferring its durable completion to the next run");
        return 1;
    }

    private static int responseBoundaries(Path fixtures) throws Exception {
        String source = Files.readString(fixtures.resolve("android-notification.json"));
        ProtocolEvent expiring = EventCodec.decode(source); check(expiring.expiresAtMs() != null && EventCodec.decode(EventCodec.encode(expiring)).expiresAtMs().equals(expiring.expiresAtMs()), "expiresAt lost");
        expectInvalid(source.replace("\"sequence\": 2", "\"sequence\": 9007199254740992"));
        expectInvalid(source.replace("\"createdAtMs\": 1700000000200", "\"createdAtMs\": 9007199254740992"));
        expectInvalid(source.replace("evt-android-note-001", repeat('e', 129)));
        expectInvalid(source.replace("\"expiresAtMs\": 1700000900200", "\"expiresAtMs\": 1"));
        String oversizedNotification = source.replace("fixture-notification-key", repeat('k', 4096)).replace("com.example.harmlessfixture", repeat('p', 255))
                .replace("Harmless Fixture", repeat('a', 4096)).replace("Fixture title", repeat('t', 8192)).replace("Fixture body", repeat('b', 65536));
        expectInvalid(oversizedNotification);
        expectInvalid(source.replace("\"payload\": {", "\"future\":\"" + repeat('u', 1_114_112) + "\",\"payload\":{"));
        ProtocolEvent invalidOutbound = new ProtocolEvent(repeat('e', 129), "device", "android", "epoch", 1,
                "clipboard.text", 1, null, Collections.singletonMap("text", "opaque"));
        try { BridgeState.fresh("device", "epoch", 4, 4).enqueue(invalidOutbound); throw new AssertionError("invalid outbound accepted"); }
        catch (IllegalArgumentException expected) { }
        try { RelayProtocol.fetch(repeat('x', 1_200_001), "0"); throw new AssertionError("oversize response accepted"); } catch (IllegalArgumentException expected) { }
        Harness harness = Harness.create(fixtures, eventFetch(fetch("9", "8", "8", EventCodec.decode(fixtures.resolve("macos-clipboard.json")))));
        check(harness.engine.step(harness.lease).status() == BridgeEngine.Status.PROTOCOL_FAILURE, "cursor replay accepted");
        return 10;
    }

    private static void expectInvalid(String json) { try { EventCodec.decode(json); throw new AssertionError("invalid numeric bound accepted"); } catch (IllegalArgumentException expected) { } }
    private static Queue<TransportResponse> eventFetch(String body) { Queue<TransportResponse> queue = new ArrayDeque<>(); queue.add(new TransportResponse(200, body)); return queue; }
    private static String fetch(String after, String next, String cursor, ProtocolEvent event) { return "{\"protocolVersion\":1,\"serverEpoch\":\"" + SERVER_A + "\",\"after\":\"" + after + "\",\"nextCursor\":\"" + next + "\",\"events\":[{\"cursor\":\"" + cursor + "\",\"event\":" + EventCodec.encode(event) + "}]}"; }
    private static String emptyFetch(String cursor) { return "{\"protocolVersion\":1,\"serverEpoch\":\"" + SERVER_A + "\",\"after\":\"" + cursor + "\",\"nextCursor\":\"" + cursor + "\",\"events\":[]}"; }
    private static String emptyFetch(String after, String next, String epoch) { return "{\"protocolVersion\":1,\"serverEpoch\":\"" + epoch + "\",\"after\":\"" + after + "\",\"nextCursor\":\"" + next + "\",\"events\":[]}"; }
    private static String publishBody(String id) { return "{\"accepted\":true,\"eventId\":\"" + id + "\",\"cursor\":\"1\",\"serverEpoch\":\"" + SERVER_A + "\",\"idempotent\":false}"; }
    private static String ackBody(String id) { return "{\"acked\":[\"" + id + "\"],\"alreadyAbsent\":[]}"; }
    private static String repeat(char value, int count) { char[] chars = new char[count]; java.util.Arrays.fill(chars, value); return new String(chars); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void deleteTree(Path root) throws Exception { try (java.util.stream.Stream<Path> paths = Files.walk(root)) { paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.delete(path); } catch (IOException error) { throw new RuntimeException(error); } }); } }

    private static final class Harness {
        final FileBridgeStateStore store; final LoopbackTransport transport; final RecordingApplier applier;
        final ConnectionOwner owner; final ConnectionOwner.Lease lease; final BridgeEngine engine;
        private Harness(FileBridgeStateStore store, LoopbackTransport transport, RecordingApplier applier, ConnectionOwner owner) {
            this.store = store; this.transport = transport; this.applier = applier; this.owner = owner; this.lease = owner.start(); this.engine = new BridgeEngine(store, transport, new FixedToken(), applier, owner);
        }
        static Harness create(Path fixtures, Queue<TransportResponse> poll) throws Exception {
            Path directory = Files.createTempDirectory("engine-harness-"); Path path = directory.resolve("state.db");
            directory.toFile().deleteOnExit(); path.toFile().deleteOnExit();
            path.resolveSibling("state.db.bak").toFile().deleteOnExit(); path.resolveSibling("state.db.new").toFile().deleteOnExit();
            FileBridgeStateStore store = FileBridgeStateStore.create(path, TestStateCipher.create(), "device-android-fixture", "epoch-android-fixture-a", 8, 8);
            LoopbackTransport transport = new LoopbackTransport(); transport.poll.addAll(poll); return new Harness(store, transport, new RecordingApplier(), new ConnectionOwner());
        }
    }
    private static final class FixedToken implements TokenProvider { @Override public String load() { return "test-token-from-provider"; } }
    private static final class RecordingApplier implements EventApplier { boolean succeed; final List<String> applied = new ArrayList<>(); @Override public boolean apply(ProtocolEvent event) { if (succeed) applied.add(event.eventId()); return succeed; } }
    private static final class InterleavingStore implements BridgeStateStore {
        private final BridgeStateStore delegate;
        private final CountDownLatch loaded = new CountDownLatch(1), attempted = new CountDownLatch(1), finished = new CountDownLatch(1);
        private volatile boolean armed, intercepted;
        private volatile Throwable producerFailure;
        InterleavingStore(BridgeStateStore delegate) { this.delegate = delegate; }
        void arm(Runnable producer) {
            armed = true;
            Thread thread = new Thread(() -> {
                try {
                    if (!loaded.await(2, TimeUnit.SECONDS)) throw new AssertionError("engine did not load after publish");
                    attempted.countDown(); producer.run();
                } catch (Throwable error) { producerFailure = error; attempted.countDown(); }
                finally { finished.countDown(); }
            }, "post-publish-producer");
            thread.start();
        }
        void awaitProducer() throws Exception {
            if (!finished.await(2, TimeUnit.SECONDS)) throw new AssertionError("post-publish producer hung");
            if (producerFailure != null) throw new AssertionError("post-publish producer failed", producerFailure);
        }
        @Override public BridgeState load() throws IOException {
            BridgeState state = delegate.load();
            if (armed) {
                armed = false; intercepted = true; loaded.countDown();
                await(attempted, "post-publish producer did not attempt enqueue");
            }
            return state;
        }
        @Override public void save(BridgeState state) throws IOException {
            if (intercepted && !Thread.holdsLock(this)) await(finished, "post-publish producer did not finish enqueue");
            delegate.save(state);
        }
        private static void await(CountDownLatch latch, String message) throws IOException {
            try { if (!latch.await(2, TimeUnit.SECONDS)) throw new IOException(message); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(message, error); }
        }
    }
    private static final class LoopbackTransport implements BridgeTransport {
        final Queue<TransportResponse> publish = new ArrayDeque<>(), poll = new ArrayDeque<>(), ack = new ArrayDeque<>();
        final List<String> published = new ArrayList<>(), pollCursors = new ArrayList<>(), acked = new ArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); volatile boolean blockPoll, cancelled;
        IOException pollError;
        Runnable onPublish, onPoll;
        @Override public TransportResponse publish(String token, ProtocolEvent event) throws IOException { published.add(EventCodec.encode(event)); if (onPublish != null) { Runnable action = onPublish; onPublish = null; action.run(); } return next(publish); }
        @Override public TransportResponse poll(String token, String cursor) throws IOException { pollCursors.add(cursor); entered.countDown(); if (onPoll != null) { Runnable action = onPoll; onPoll = null; action.run(); } if (pollError != null) throw pollError; if (blockPoll) { try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } throw new CancelledTransportException(); } return next(poll); }
        @Override public TransportResponse acknowledge(String token, String deviceId, List<String> ids) throws IOException { acked.addAll(ids); return next(ack); }
        @Override public void cancel() { cancelled = true; release.countDown(); }
        private static TransportResponse next(Queue<TransportResponse> queue) throws IOException { TransportResponse response = queue.poll(); if (response == null) throw new IOException("no scripted response"); return response; }
    }
}
