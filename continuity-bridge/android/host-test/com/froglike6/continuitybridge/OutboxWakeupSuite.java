package com.froglike6.continuitybridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

final class OutboxWakeupSuite {
    private static final String SERVER = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static int cases;
    private OutboxWakeupSuite() { }

    static int run() throws Exception {
        persistedWorkWakesLongPoll(false);
        persistedWorkWakesLongPoll(true);
        wakeBeforePollAndDuringConnect();
        acceptedPostsSurviveWake();
        durableCallbackAndListenerOwnership();
        System.out.println("OUTBOX_WAKEUP_OK cases=" + cases);
        return cases;
    }

    private static void persistedWorkWakesLongPoll(boolean notification) throws Exception {
        Path root = Files.createTempDirectory("outbox-wakeup-");
        ExchangeFactory factory = new ExchangeFactory(); factory.blockFirstPoll = true;
        RelayTransport transport = transport(factory);
        try {
            FileBridgeStateStore store = FileBridgeStateStore.create(root.resolve("state"), TestStateCipher.create(), "device", "epoch", 8, 8);
            DurableOutbox outbox = new DurableOutbox(store, () -> "local-1", new ObservationWindow(1000, 8));
            outbox.observeEnqueue(transport::wakePoll);
            ConnectionOwner owner = new ConnectionOwner(); ConnectionOwner.Lease lease = owner.start();
            BridgeEngine engine = new BridgeEngine(store, transport, () -> "fixture-token", event -> true, owner);
            BridgeEngine.Result[] result = new BridgeEngine.Result[1];
            Thread waiting = new Thread(() -> result[0] = engine.step(lease), "test-long-poll");
            waiting.start();
            try {
                check(factory.pollEntered.await(2, TimeUnit.SECONDS), "long poll did not begin");
                if (notification) check(outbox.enqueueNotification(NotificationMapper.map("key", "pkg", "App", "title", "body", null, 1), 1), "notification save failed");
                else check(outbox.captureClipboard("fixture clipboard", null, "timestamp:1", 1) == CaptureResult.ENQUEUED, "clipboard save failed");
                waiting.join(1500);
                check(!waiting.isAlive(), (notification ? "notification" : "clipboard") + " persisted while GET blocked but poll did not wake");
                check(result[0].status() == BridgeEngine.Status.OUTBOX_READY && owner.owns(lease), "wakeup cancelled lease or entered retry");
                check(store.load().outbox().size() == 1 && store.load().cursor().equals("0"), "wakeup lost durable work or advanced cursor");
                check(engine.step(lease).status() == BridgeEngine.Status.CONNECTED, "wakeup did not allow immediate publish");
                check(store.load().outbox().isEmpty() && factory.published == 1, "persisted work was not published exactly once");
            } finally { engine.cancel(lease); waiting.join(2000); }
        } finally { transport.cancel(); delete(root); }
    }

    private static void wakeBeforePollAndDuringConnect() throws Exception {
        ExchangeFactory factory = new ExchangeFactory(); RelayTransport transport = transport(factory);
        try {
            transport.wakePoll(); transport.wakePoll();
            expectWake(transport);
            check(!factory.latest.opened && factory.responseReads == 0, "pending wake opened a blocking GET");
            check(transport.poll("fixture-token", "0").status() == 200, "coalesced wake did not clear after one pass");
            factory.onOpen = transport::wakePoll;
            expectWake(transport);
            check(!factory.latest.opened && factory.responseReads == 1, "wake during URL connection creation was lost");
            factory.onConnect = transport::wakePoll;
            expectWake(transport);
            check(factory.latest.opened && factory.responseReads == 1, "wake before socket establishment entered long response wait");
            check(transport.poll("fixture-token", "0").status() == 200, "poll did not recover from connect-boundary wake");
            transport.cancel(); transport.wakePoll();
            try { transport.poll("fixture-token", "0"); throw new AssertionError("cancelled transport restarted on wake"); }
            catch (CancelledTransportException expected) { cases++; }
            RelayTransport restarted = transport(new ExchangeFactory());
            try { check(restarted.poll("fixture-token", "0").status() == 200, "old cancellation leaked into new transport"); }
            finally { restarted.cancel(); }
        } finally { transport.cancel(); }
    }

    private static void acceptedPostsSurviveWake() throws Exception {
        ExchangeFactory factory = new ExchangeFactory(); RelayTransport transport = transport(factory);
        try {
            Runnable wake = () -> {
                transport.wakePoll();
                check(factory.latest.released.getCount() == 1, "outbox wake disconnected an accepted POST");
            };
            factory.onResponse = wake;
            ProtocolEvent event = new ProtocolEvent("local-1", "device", "android", "epoch", 1, "clipboard.text", 1, null,
                    java.util.Collections.singletonMap("text", "fixture"));
            check(transport.publish("fixture-token", event).status() == 201, "publish response lost during wake");
            expectWake(transport);
            check(transport.poll("fixture-token", "0").status() == 200, "publish wake cancelled future work");
            factory.onResponse = wake;
            TransportResponse ack = transport.acknowledge("fixture-token", "device", java.util.Collections.singletonList("remote-1"));
            check(ack.status() == 200 && ack.body().contains("remote-1"), "ACK receipt lost during wake");
            expectWake(transport);
            check(transport.poll("fixture-token", "0").status() == 200, "ACK wake cancelled future work");
        } finally { transport.cancel(); }
    }

    private static void durableCallbackAndListenerOwnership() throws Exception {
        Path root = Files.createTempDirectory("outbox-signal-");
        try {
            FileBridgeStateStore backing = FileBridgeStateStore.create(root.resolve("state"), TestStateCipher.create(), "device", "epoch", 8, 8);
            boolean[] fail = {true}; int[] sequence = {0}; int[] wakes = {0};
            BridgeStateStore store = new BridgeStateStore() {
                @Override public BridgeState load() throws IOException { return backing.load(); }
                @Override public void save(BridgeState state) throws IOException {
                    if (fail[0]) throw new IOException("fixture_storage_failure");
                    backing.save(state);
                }
            };
            DurableOutbox outbox = new DurableOutbox(store, () -> "saved-" + ++sequence[0], new ObservationWindow(1000, 8));
            Runnable first = () -> {
                check(!Thread.holdsLock(store), "enqueue callback held the durable store lock");
                try { check(!backing.load().outbox().isEmpty(), "wake ran before durable save"); }
                catch (IOException error) { throw new AssertionError(error); }
                wakes[0]++;
            };
            outbox.observeEnqueue(first);
            check(outbox.captureClipboard("fixture", null, "timestamp:1", 1) == CaptureResult.UNAVAILABLE && wakes[0] == 0,
                    "failed clipboard save woke poll");
            fail[0] = false;
            check(outbox.captureClipboard("fixture", null, "timestamp:1", 2) == CaptureResult.ENQUEUED && wakes[0] == 1,
                    "saved clipboard did not wake once");
            check(outbox.captureClipboard("fixture", null, "timestamp:1", 3) == CaptureResult.DUPLICATE && wakes[0] == 1,
                    "duplicate clipboard woke poll");
            backing.save(backing.load().markRemoteApply("remote-1", "timestamp:remote"));
            check(outbox.captureClipboard("remote", "remote-1", "timestamp:remote", 4) == CaptureResult.REMOTE_SKIPPED && wakes[0] == 1,
                    "remote clipboard confirmation woke outbound poll");
            NotificationFields fields = NotificationMapper.map("key", "pkg", "App", "title", "body", null, 5);
            fail[0] = true;
            check(!outbox.enqueueNotification(fields, 5) && wakes[0] == 1, "failed notification save woke poll");
            fail[0] = false;
            check(outbox.enqueueNotification(fields, 6) && wakes[0] == 2, "saved notification did not wake once");
            int[] restartedWakes = {0}; Runnable restarted = () -> restartedWakes[0]++;
            outbox.observeEnqueue(restarted); outbox.stopObservingEnqueue(first);
            check(outbox.enqueueNotification(fields, 7) && restartedWakes[0] == 1 && wakes[0] == 2,
                    "stale worker cleanup removed new run observer");
            outbox.stopObservingEnqueue(restarted);
            check(outbox.enqueueNotification(fields, 8) && restartedWakes[0] == 1, "stopped worker retained observer");
        } finally { delete(root); }
    }

    private static void expectWake(RelayTransport transport) throws Exception {
        try { transport.poll("fixture-token", "0"); throw new AssertionError("pending outbox wake was lost"); }
        catch (PollWakeException expected) { cases++; }
    }

    private static RelayTransport transport(ExchangeFactory factory) {
        return new RelayTransport(null, new RelayTransport.Settings() {
            @Override public String endpoint() { return "https://relay.example:8443"; }
            @Override public String pin() { return ""; }
            @Override public boolean systemTrust() { return true; }
        }, factory, metadata -> { throw new AssertionError("unexpected transport diagnostic"); });
    }

    private static final class ExchangeFactory implements RelayTransport.ConnectionFactory {
        final CountDownLatch pollEntered = new CountDownLatch(1);
        boolean blockFirstPoll;
        int polls;
        int published;
        int responseReads;
        Runnable onOpen;
        Runnable onConnect;
        Runnable onResponse;
        Exchange latest;
        @Override public HttpsURLConnection open(URL target) {
            boolean poll = target.getQuery() != null;
            latest = new Exchange(target, poll && blockFirstPoll && polls++ == 0, this);
            Runnable action = onOpen; onOpen = null; if (action != null) action.run();
            return latest;
        }
    }

    private static final class Exchange extends HttpsURLConnection {
        final boolean block;
        final ExchangeFactory factory;
        final CountDownLatch released = new CountDownLatch(1);
        final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
        volatile boolean opened;
        Exchange(URL target, boolean block, ExchangeFactory factory) { super(target); this.block = block; this.factory = factory; }
        @Override public void connect() {
            Runnable action = factory.onConnect; factory.onConnect = null; if (action != null) action.run();
            opened = true;
        }
        @Override public void disconnect() { if (opened) released.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return ""; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
        @Override public int getResponseCode() throws IOException {
            if (!opened) connect();
            factory.responseReads++;
            Runnable action = factory.onResponse; factory.onResponse = null; if (action != null) action.run();
            if (block) {
                factory.pollEntered.countDown();
                try { if (!released.await(5, TimeUnit.SECONDS)) throw new IOException("test_poll_timeout"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                throw new IOException("test_poll_disconnected");
            }
            if ("POST".equals(getRequestMethod()) && url.getPath().equals("/v1/events")) factory.published++;
            return "POST".equals(getRequestMethod()) && url.getPath().equals("/v1/events") ? 201 : 200;
        }
        @Override public ByteArrayOutputStream getOutputStream() { return requestBody; }
        @Override public java.io.InputStream getInputStream() {
            String response;
            if (url.getPath().equals("/v1/acks")) response = "{\"acked\":[\"remote-1\"],\"alreadyAbsent\":[]}";
            else if ("POST".equals(getRequestMethod())) response = "{\"accepted\":true,\"eventId\":\"local-1\",\"cursor\":\"1\",\"serverEpoch\":\"" + SERVER + "\",\"idempotent\":false}";
            else response = "{\"protocolVersion\":1,\"serverEpoch\":\"" + SERVER + "\",\"after\":\"0\",\"nextCursor\":\"0\",\"events\":[]}";
            return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); cases++; }
    private static void delete(Path root) throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
}
