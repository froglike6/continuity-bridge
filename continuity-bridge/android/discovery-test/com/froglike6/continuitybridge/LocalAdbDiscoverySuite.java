package com.froglike6.continuitybridge;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class LocalAdbDiscoverySuite {
    private LocalAdbDiscoverySuite() { }

    public static void main(String[] args) {
        int failures = 0;
        failures += run("live port selected after stale first advertisement", LocalAdbDiscoverySuite::staleThenLive);
        failures += run("nonlocal advertisement ignored before local live port", LocalAdbDiscoverySuite::nonlocalThenLive);
        failures += run("pending discovery interruption propagates and stops NSD", LocalAdbDiscoverySuite::interrupted);
        failures += run("all closed candidates expire at bounded discovery deadline", LocalAdbDiscoverySuite::allClosed);
        failures += run("discovery start failure propagates and stops NSD", LocalAdbDiscoverySuite::startFailure);
        System.out.println("LocalAdbDiscoverySuite: " + (5 - failures) + " passed, " + failures + " failed");
        if (failures != 0) throw new AssertionError("Local ADB discovery regressions: " + failures);
    }

    private static void staleThenLive() throws Exception {
        // Given: a stale advertisement precedes a listening local endpoint.
        RecordingNsdManager manager = new RecordingNsdManager();
        try (ServerSocket live = listener(); DiscoveryCall call = new DiscoveryCall(manager)) {
            int stale = closedPort();
            manager.awaitStarted();

            // When: NSD resolves the stale port first, then the live port.
            manager.advertise(loopback(), stale);
            manager.advertise(loopback(), live.getLocalPort());

            // Then: the live endpoint is selected and its probe and NSD are closed.
            int selected = call.port();
            check(selected == live.getLocalPort(), "Selected stale port " + selected
                    + " instead of live port " + live.getLocalPort() + " (stale=" + stale + ")");
            checkProbeClosed(live);
            check(manager.stops.get() == 1, "Successful discovery must stop NSD once");
        }
    }

    private static void nonlocalThenLive() throws Exception {
        // Given: a remote advertisement points at another listening loopback port.
        RecordingNsdManager manager = new RecordingNsdManager();
        InetAddress remote = InetAddress.getByAddress(new byte[] {(byte) 203, 0, 113, 10});
        try (ServerSocket remotePort = listener(); ServerSocket live = listener();
                DiscoveryCall call = new DiscoveryCall(manager)) {
            manager.awaitStarted();

            // When: a nonlocal host resolves before a valid local endpoint.
            manager.advertise(remote, remotePort.getLocalPort());
            manager.advertise(loopback(), live.getLocalPort());

            // Then: only the local advertisement may cause a loopback connection.
            check(call.port() == live.getLocalPort(), "A nonlocal advertisement was selected");
            remotePort.setSoTimeout(100);
            try (Socket unexpected = remotePort.accept()) {
                throw new AssertionError("Nonlocal advertisement triggered a probe: " + unexpected);
            } catch (SocketTimeoutException expected) {
                check(manager.stops.get() == 1, "Successful discovery must stop NSD once");
            }
        }
    }

    private static void interrupted() throws Exception {
        // Given: discovery is running without any resolved candidates.
        RecordingNsdManager manager = new RecordingNsdManager();
        try (DiscoveryCall call = new DiscoveryCall(manager)) {
            manager.awaitStarted();

            // When: its calling worker is interrupted.
            call.worker.interrupt();

            // Then: the caller receives cancellation and NSD is stopped.
            check(call.failure() instanceof InterruptedException, "Expected InterruptedException");
            check(manager.stops.get() == 1, "Interrupted discovery must stop NSD once");
        }
    }

    private static void allClosed() throws Exception {
        // Given: the only advertisement is a closed local port.
        RecordingNsdManager manager = new RecordingNsdManager();
        int stale = closedPort();
        long started = System.nanoTime();
        try (DiscoveryCall call = new DiscoveryCall(manager)) {
            manager.awaitStarted();

            // When: the stale endpoint resolves without any live successor.
            manager.advertise(loopback(), stale);

            // Then: discovery expires instead of returning the closed endpoint.
            check(call.failure() instanceof IOException, "Expected IOException for closed candidates");
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            check(elapsed >= 7_000 && elapsed < 12_000, "Unexpected discovery deadline: " + elapsed + " ms");
            check(manager.stops.get() == 1, "Expired discovery must stop NSD once");
        }
    }

    private static void startFailure() throws Exception {
        // Given: NSD has registered the discovery listener.
        RecordingNsdManager manager = new RecordingNsdManager();
        try (DiscoveryCall call = new DiscoveryCall(manager)) {
            manager.awaitStarted();

            // When: Android reports that discovery could not start.
            manager.discovery.onStartDiscoveryFailed(LocalAdbDiscovery.CONNECT, 0);

            // Then: the caller receives a failure and NSD is stopped.
            check(call.failure() instanceof IOException, "Expected IOException for failed NSD start");
            check(manager.stops.get() == 1, "Failed discovery must stop NSD once");
        }
    }

    private static InetAddress loopback() throws IOException {
        return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    }

    private static ServerSocket listener() throws IOException {
        return new ServerSocket(0, 1, loopback());
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = listener()) { return socket.getLocalPort(); }
    }

    private static void checkProbeClosed(ServerSocket listener) throws IOException {
        listener.setSoTimeout(2_000);
        try (Socket probe = listener.accept()) {
            probe.setSoTimeout(2_000);
            check(probe.getInputStream().read() == -1, "Probe connection must close without sending data");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int run(String name, Scenario scenario) {
        try {
            scenario.run();
            System.out.println("PASS " + name);
            return 0;
        } catch (Exception | AssertionError failure) {
            System.err.println("FAIL " + name + ": " + failure);
            failure.printStackTrace(System.err);
            return 1;
        }
    }

    @FunctionalInterface
    private interface Scenario { void run() throws Exception; }

    private static final class DiscoveryCall implements AutoCloseable {
        final FutureTask<Integer> result;
        final Thread worker;

        DiscoveryCall(RecordingNsdManager manager) {
            result = new FutureTask<>(() -> LocalAdbDiscovery.find(new Context(manager), LocalAdbDiscovery.CONNECT));
            worker = new Thread(result, "local-adb-discovery-test");
            worker.setDaemon(true);
            worker.start();
        }

        int port() throws Exception { return result.get(12, TimeUnit.SECONDS); }

        Throwable failure() throws Exception {
            try {
                throw new AssertionError("Expected discovery failure, returned port " + port());
            } catch (ExecutionException failure) {
                return failure.getCause();
            }
        }

        @Override public void close() {
            worker.interrupt();
            try { worker.join(2_000); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Test cleanup interrupted", error);
            }
            check(!worker.isAlive(), "Discovery worker did not stop");
        }
    }

    private static final class RecordingNsdManager extends NsdManager {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicInteger stops = new AtomicInteger();
        volatile DiscoveryListener discovery;

        @Override public void discoverServices(String type, int protocol, DiscoveryListener listener) {
            check(protocol == PROTOCOL_DNS_SD, "Expected DNS-SD discovery");
            discovery = listener;
            listener.onDiscoveryStarted(type);
            started.countDown();
        }

        @Override public void stopServiceDiscovery(DiscoveryListener listener) {
            check(listener == discovery, "Stopped a different NSD listener");
            stops.incrementAndGet();
        }

        @Override public void resolveService(NsdServiceInfo service, ResolveListener listener) {
            listener.onServiceResolved(service);
        }

        void awaitStarted() throws InterruptedException {
            check(started.await(2, TimeUnit.SECONDS), "Discovery did not start");
        }

        void advertise(InetAddress host, int port) {
            discovery.onServiceFound(new NsdServiceInfo(host, port));
        }
    }
}
