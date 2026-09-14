package com.froglike6.continuitybridge;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class LocalAdbDiscovery {
    static final String PAIRING = "_adb-tls-pairing._tcp.";
    static final String CONNECT = "_adb-tls-connect._tcp.";

    static int find(Context context, String type) throws IOException, InterruptedException {
        final NsdManager manager = context.getSystemService(NsdManager.class);
        if (manager == null) throw new IOException("adb_discovery_unavailable");
        final BlockingQueue<Integer> candidates = new LinkedBlockingQueue<>();
        NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) { }
            @Override public void onDiscoveryStopped(String serviceType) { }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) { candidates.offer(0); }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) { }
            @Override public void onServiceLost(NsdServiceInfo service) { }
            @Override public void onServiceFound(NsdServiceInfo service) {
                try {
                    manager.resolveService(service, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) { }
                        @Override public void onServiceResolved(NsdServiceInfo info) {
                            if (isLocal(info.getHost()) && info.getPort() > 0 && info.getPort() <= 65_535) {
                                candidates.offer(info.getPort());
                            }
                        }
                    });
                } catch (IllegalArgumentException error) {
                    android.util.Log.w("EmbeddedClipboard", "discovery_resolve_unavailable");
                }
            }
        };
        manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                Integer candidate = candidates.poll(remaining, TimeUnit.NANOSECONDS);
                if (candidate == null || candidate == 0) break;
                try (Socket probe = new Socket()) {
                    probe.connect(new InetSocketAddress("127.0.0.1", candidate), 250);
                    return candidate;
                } catch (IOException unavailable) {
                    // mDNS can retain ports from earlier wireless debugging sessions.
                }
            }
        }
        finally {
            try { manager.stopServiceDiscovery(discovery); }
            catch (IllegalArgumentException error) { android.util.Log.w("EmbeddedClipboard", "discovery_already_stopped"); }
        }
        throw new IOException("adb_discovery_unavailable");
    }

    static boolean isLocal(InetAddress address) {
        if (address == null || address.isAnyLocalAddress()) return false;
        if (address.isLoopbackAddress()) return true;
        try { return NetworkInterface.getByInetAddress(address) != null; }
        catch (SocketException error) { return false; }
    }
}
