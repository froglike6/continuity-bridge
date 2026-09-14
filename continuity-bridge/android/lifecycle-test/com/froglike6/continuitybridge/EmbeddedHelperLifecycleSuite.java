package com.froglike6.continuitybridge;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public final class EmbeddedHelperLifecycleSuite {
    private EmbeddedHelperLifecycleSuite() { }
    public static void main(String[] arguments) throws Exception {
        int failures = 0;
        failures += run("automatic_opt_out_survives_late_success", EmbeddedHelperLifecycleSuite::optOutSurvivesLateSuccess);
        failures += run("new_start_survives_stale_success", () -> startSurvivesStaleCompletion(false));
        failures += run("new_start_survives_stale_failure", () -> startSurvivesStaleCompletion(true));
        failures += run("opt_out_rejects_late_helper", EmbeddedHelperLifecycleSuite::optOutRejectsLateHelper);
        failures += run("opt_out_rejects_helper_after_worker_success", EmbeddedHelperLifecycleSuite::optOutRejectsHelperAfterWorkerSuccess);
        failures += run("stop_cancels_work_and_persists_intent", EmbeddedHelperLifecycleSuite::stopCancelsWork);
        failures += run("opt_out_preserves_running_helper", EmbeddedHelperLifecycleSuite::optOutPreservesRunningHelper);
        failures += run("pair_opt_out_starts_manually", EmbeddedHelperLifecycleSuite::pairOptOutStartsManually);
        failures += run("automatic_backend_requires_api_33", EmbeddedHelperLifecycleSuite::automaticRequiresApi33);
        failures += run("pairing_available_on_api_30", EmbeddedHelperLifecycleSuite::pairingAvailableOnApi30);
        failures += run("approval_required_does_not_poll", EmbeddedHelperLifecycleSuite::approvalDoesNotPoll);
        failures += run("boot_requires_all_opt_ins", EmbeddedHelperLifecycleSuite::bootRequiresAllOptIns);
        if (failures != 0) throw new AssertionError("embedded_lifecycle_failures=" + failures);
        System.out.println("embedded_lifecycle_passed=12");
    }

    private static void optOutSurvivesLateSuccess() throws Exception {
        // Given an automatic start whose worker has not yet completed.
        Fixture fixture = new Fixture(35, true);
        fixture.manager.setAutomaticRecovery(true);
        Handler.drain();
        fixture.adb.duringStart = () -> { fixture.manager.setAutomaticRecovery(false); Handler.drain(); };
        // When opt-out is processed before the backend's late success callback.
        fixture.worker.runNext();
        Handler.drain();
        // Then completion cannot restore the preference or the pending opt-in.
        check(!fixture.preferences.automatic() && !fixture.manager.automaticRecovery(), "late success re-enabled automatic recovery");
    }

    private static void startSurvivesStaleCompletion(boolean failure) throws Exception {
        // Given an in-flight start, and a stop immediately followed by a new start.
        Fixture fixture = new Fixture(35, true);
        fixture.manager.start();
        Handler.drain();
        fixture.adb.duringStart = () -> {
            fixture.manager.onUserStop();
            fixture.manager.start();
            Handler.drain();
            if (failure) throw new IOException("cancelled_worker");
        };
        // When the old worker finishes after both requests.
        fixture.worker.runNext();
        Handler.drain();
        // Then the new request is queued and can connect with a fresh nonce.
        check(fixture.worker.pending() == 1, "latest start lost after stale completion");
        fixture.worker.runNext();
        Handler.drain();
        check(fixture.adb.starts.size() == 2, "replacement start was not executed");
        TestBinder helper = new TestBinder();
        check(fixture.manager.attach(fixture.adb.starts.get(1).nonce, helper), "new helper rejected");
        Handler.drain();
        check(fixture.manager.binder() == helper && fixture.manager.state() == EmbeddedHelperManager.State.READY,
                "new helper did not become ready");
    }

    private static void optOutRejectsLateHelper() throws Exception {
        // Given the nonce belonging to an in-flight automatic start.
        Fixture fixture = new Fixture(35, true);
        fixture.manager.setAutomaticRecovery(true);
        Handler.drain();
        boolean[] accepted = { true };
        fixture.adb.duringStart = () -> {
            fixture.manager.setAutomaticRecovery(false);
            Handler.drain();
            accepted[0] = fixture.manager.attach(fixture.adb.starts.get(0).nonce, new TestBinder());
        };
        // When that helper arrives after opt-out.
        fixture.worker.runNext();
        Handler.drain();
        // Then the cancelled nonce is rejected before the helper can be installed.
        check(!accepted[0] && fixture.manager.binder() == null, "cancelled automatic helper accepted");
        check(fixture.adb.starts.get(0).gate != null && !fixture.adb.starts.get(0).gate.active(), "cancelled operation gate is active");
    }

    private static void stopCancelsWork() throws Exception {
        // Given a running helper and persisted bridge intent.
        Fixture fixture = readyFixture();
        TestBinder helper = (TestBinder) fixture.manager.binder();
        fixture.preferences.bridgeRequested(true);
        // When the user explicitly stops the helper.
        fixture.manager.onUserStop();
        Handler.drain();
        fixture.worker.runAll();
        Handler.drain();
        // Then work is cancelled, the helper is destroyed, and reboot intent is off.
        check(fixture.adb.cancellations > 0 && !fixture.preferences.bridgeRequested(), "stop did not cancel work and persist intent");
        check(helper.destroyed && fixture.manager.binder() == null && fixture.manager.state() == EmbeddedHelperManager.State.STOPPED,
                "stop retained the helper");
        check(Handler.delayedCount() == 0, "stop retained a delayed callback");
    }

    private static void optOutRejectsHelperAfterWorkerSuccess() throws Exception {
        // Given automatic startup completed but the helper has not attached yet.
        Fixture fixture = new Fixture(35, true);
        fixture.manager.setAutomaticRecovery(true);
        Handler.drain();
        fixture.worker.runNext();
        Handler.drain();
        String nonce = fixture.adb.starts.get(0).nonce;
        // When automatic recovery is disabled during the attachment wait.
        fixture.manager.setAutomaticRecovery(false);
        Handler.drain();
        // Then the completed automatic attempt cannot install its late helper.
        check(!fixture.manager.attach(nonce, new TestBinder()) && !fixture.preferences.automatic(),
                "automatic helper accepted after opt-out during attachment wait");
    }

    private static void optOutPreservesRunningHelper() throws Exception {
        // Given a live helper after an automatic launch.
        Fixture fixture = readyFixture();
        TestBinder helper = (TestBinder) fixture.manager.binder();
        // When only automatic recovery is disabled.
        fixture.manager.setAutomaticRecovery(false);
        Handler.drain();
        fixture.worker.runAll();
        Handler.drain();
        // Then the current helper stays available without the reboot opt-in.
        check(fixture.manager.binder() == helper && !helper.destroyed && !fixture.preferences.automatic(), "opt-out stopped the running helper");
    }

    private static void pairOptOutStartsManually() throws Exception {
        // Given pairing requested with automatic recovery enabled.
        Fixture fixture = new Fixture(35, false);
        fixture.manager.pair(12345, "123456", true);
        Handler.drain();
        fixture.adb.duringPair = () -> { fixture.manager.setAutomaticRecovery(false); Handler.drain(); };
        // When opt-out is processed before pairing completes.
        fixture.worker.runNext();
        Handler.drain();
        fixture.worker.runNext();
        Handler.drain();
        // Then pairing remains usable but the next startup is manual.
        check(fixture.preferences.paired() && !fixture.adb.starts.get(0).automatic, "pairing completion re-enabled automatic startup");
    }

    private static void automaticRequiresApi33() throws Exception {
        for (int sdk : new int[] { 30, 32 }) {
            // Given a supported pairing device below the automatic recovery minimum.
            Fixture fixture = new Fixture(sdk, true);
            // When callers bypass the UI and request automatic recovery.
            fixture.preferences.automatic(true);
            fixture.manager.setAutomaticRecovery(true);
            Handler.drain();
            // Then neither the backend preference nor the manager accepts the opt-in.
            check(!fixture.preferences.automatic() && !fixture.manager.automaticRecovery() && fixture.worker.pending() == 0,
                    "automatic recovery accepted on api=" + sdk);
        }
    }

    private static void pairingAvailableOnApi30() throws Exception {
        // Given Android 11 and an unchecked backend request for automatic pairing.
        Fixture fixture = new Fixture(30, false);
        // When pairing succeeds.
        fixture.manager.pair(12345, "123456", true);
        Handler.drain();
        fixture.worker.runNext();
        Handler.drain();
        fixture.worker.runNext();
        Handler.drain();
        // Then manual helper startup remains available without automatic privileges.
        check(fixture.adb.pairs == 1 && fixture.preferences.paired() && !fixture.adb.starts.get(0).automatic,
                "api 30 pairing requested automatic privileges");
    }

    private static void approvalDoesNotPoll() throws Exception {
        // Given Android reports that wireless debugging still needs system approval.
        Fixture fixture = new Fixture(35, true);
        fixture.adb.duringStart = () -> { throw new LocalAdbOperations.NeedsApprovalException(); };
        // When automatic startup fails at the system approval boundary.
        fixture.manager.setAutomaticRecovery(true);
        Handler.drain();
        fixture.worker.runNext();
        Handler.drain();
        // Then recovery waits for an explicit start or a network event instead of polling.
        check(fixture.manager.state() == EmbeddedHelperManager.State.DEBUGGING_REQUIRED && Handler.delayedCount() == 0,
                "approval-required failure scheduled a repeated prompt");
    }

    private static void bootRequiresAllOptIns() throws Exception {
        for (int sdk : new int[] { 30, 32, 33, 35 }) {
            for (int flags = 0; flags < 8; flags++) {
                // Given all combinations of persisted pairing, auto recovery, and bridge intent.
                Fixture fixture = new Fixture(sdk, (flags & 1) != 0);
                fixture.preferences.automatic((flags & 2) != 0);
                fixture.preferences.bridgeRequested((flags & 4) != 0);
                // When a boot broadcast is received.
                new HelperBootReceiver().onReceive(fixture.context, new Intent(Intent.ACTION_BOOT_COMPLETED));
                // Then only a fully opted-in supported device starts the bridge.
                check(fixture.context.foregroundStarts == (sdk >= 33 && flags == 7 ? 1 : 0), "boot restore gate failed");
            }
        }
    }

    private static Fixture readyFixture() throws Exception {
        Fixture fixture = new Fixture(35, true);
        fixture.manager.setAutomaticRecovery(true);
        Handler.drain();
        fixture.adb.duringStart = () -> {
            check(fixture.manager.attach(fixture.adb.starts.get(0).nonce, new TestBinder()), "fixture helper rejected");
            Handler.drain();
        };
        fixture.worker.runNext();
        Handler.drain();
        return fixture;
    }

    private interface Test { void run() throws Exception; }
    private static int run(String name, Test test) throws Exception {
        try { test.run(); System.out.println("PASS " + name); return 0; }
        catch (AssertionError failure) { System.out.println("FAIL " + name + ": " + failure.getMessage()); return 1; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static Field field(String name) throws ReflectiveOperationException {
        Field field = EmbeddedHelperManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Fixture {
        final Context context = new Context();
        final EmbeddedHelperPreferences preferences;
        final EmbeddedHelperManager manager;
        final LocalAdbOperations adb;
        final WorkerQueue worker = new WorkerQueue();
        Fixture(int sdk, boolean paired) throws Exception {
            Handler.reset();
            Build.VERSION.SDK_INT = sdk;
            preferences = new EmbeddedHelperPreferences(context);
            preferences.paired(paired);
            field("instance").set(null, null);
            manager = EmbeddedHelperManager.get(context);
            ((ExecutorService) field("io").get(manager)).shutdownNow();
            field("io").set(manager, worker);
            adb = (LocalAdbOperations) field("adb").get(manager);
        }
    }

    private static final class WorkerQueue extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;
        int pending() { return tasks.size(); }
        void runNext() { check(!tasks.isEmpty(), "expected a queued worker"); tasks.remove().run(); }
        void runAll() { while (!tasks.isEmpty()) runNext(); }
        @Override public void execute(Runnable task) { tasks.add(task); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; List<Runnable> pending = new ArrayList<>(tasks); tasks.clear(); return pending; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }

    private static final class TestBinder implements IBinder, IShizukuClipboard {
        boolean destroyed;
        @Override public boolean isBinderAlive() { return !destroyed; }
        @Override public void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {
            if (destroyed) throw new RemoteException();
        }
        @Override public void destroy() { destroyed = true; }
    }
}
