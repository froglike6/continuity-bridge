package com.froglike6.continuitybridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import io.github.muntashirakon.adb.PairingConnectionCtx;

public final class EmbeddedHelperInstrumentationTest extends InstrumentationTestCase {
    public void testIdentityPersistsOnlyEncryptedMaterialAndRestoresSameKey() throws Exception {
        Context target = getInstrumentation().getTargetContext();
        LocalAdbIdentity.Identity first = new LocalAdbIdentity(target).load();
        LocalAdbIdentity.Identity second = new LocalAdbIdentity(target).load();
        assertTrue(Arrays.equals(first.certificate.getEncoded(), second.certificate.getEncoded()));
        assertTrue(Arrays.equals(first.privateKey.getEncoded(), second.privateKey.getEncoded()));
        SharedPreferences preferences = target.getSharedPreferences("bridge_local_adb_identity", Context.MODE_PRIVATE);
        String sealed = preferences.getString("encrypted_identity", "");
        assertEquals(1, preferences.getAll().size());
        assertTrue(sealed.length() > 100);
        assertFalse(sealed.contains(Base64.getEncoder().encodeToString(first.privateKey.getEncoded())));
    }

    public void testDiscoveryAcceptsOnlyThisDevicesAddresses() throws Exception {
        assertTrue(LocalAdbDiscovery.isLocal(InetAddress.getByName("127.0.0.1")));
        assertTrue(LocalAdbDiscovery.isLocal(InetAddress.getByName("::1")));
        assertFalse(LocalAdbDiscovery.isLocal(InetAddress.getByName("0.0.0.0")));
        assertFalse(LocalAdbDiscovery.isLocal(InetAddress.getByName("203.0.113.20")));
        assertFalse(LocalAdbDiscovery.isLocal(null));
    }

    public void testOrdinaryAppUidCannotAttachHelperBinder() {
        Bundle extras = new Bundle();
        extras.putBinder("helper", new Binder());
        try {
            getInstrumentation().getTargetContext().getContentResolver().call(
                    Uri.parse("content://" + ClipboardHelperProvider.AUTHORITY), "attach", "invalid", extras);
            fail("An app UID must not attach a shell helper.");
        } catch (SecurityException expected) { }
    }

    public void testClosingPairingBeforeStartRejectsWithoutConnecting() throws Exception {
        LocalAdbIdentity.Identity key = new LocalAdbIdentity(getInstrumentation().getTargetContext()).load();
        try (PairingConnectionCtx pairing = new PairingConnectionCtx("127.0.0.1", 9, new byte[]{1, 2, 3},
                key.privateKey, key.certificate, "Continuity Test")) {
            pairing.close();
            long before = android.os.SystemClock.elapsedRealtime();
            try { pairing.start(); fail("A closed pairing session must not start."); }
            catch (IOException expected) { }
            assertTrue(android.os.SystemClock.elapsedRealtime() - before < 500);
        }
    }

    public void testCancellingStalledTlsUnblocksAndAllowsNextOperation() throws Exception {
        LocalAdbOperations operations = new LocalAdbOperations(getInstrumentation().getTargetContext());
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Future<Boolean> attempt = worker.submit(new java.util.concurrent.Callable<Boolean>() {
                @Override public Boolean call() throws Exception {
                    try {
                        operations.pair(server.getLocalPort(), "123456", new LocalAdbOperations.Gate() {
                            @Override public boolean active() { return true; }
                        });
                        return false;
                    } catch (IOException | InterruptedException cancelled) { return true; }
                }
            });
            try (Socket stalled = server.accept()) {
                operations.cancel();
                assertTrue(attempt.get(3, TimeUnit.SECONDS));
            }
            Future<Boolean> next = worker.submit(new java.util.concurrent.Callable<Boolean>() {
                @Override public Boolean call() { return !Thread.currentThread().isInterrupted(); }
            });
            assertTrue(next.get(1, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
    }
}
