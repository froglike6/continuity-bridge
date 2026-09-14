package com.froglike6.continuitybridge;

final class ServiceRunCoordinator {
    private static final ServiceRunCoordinator PROCESS = new ServiceRunCoordinator();

    static final class Run {
        private long wakeRevision;
        private Run() { }

        synchronized long wakeRevision() { return wakeRevision; }
        synchronized void wake() { wakeRevision++; notifyAll(); }
        synchronized void awaitWake(long observedRevision, long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (wakeRevision == observedRevision) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
    }

    private Run active;

    static ServiceRunCoordinator process() { return PROCESS; }

    synchronized boolean hasActiveRun() { return active != null; }

    synchronized Run start() {
        if (active != null) return null;
        active = new Run();
        return active;
    }

    synchronized boolean owns(Run run) { return active == run; }

    synchronized boolean publish(Run run, Runnable action) {
        if (active != run) return false;
        action.run();
        return true;
    }

    synchronized boolean finish(Run run) {
        if (active != run) return false;
        active = null;
        return true;
    }

    synchronized boolean cancel(Run run) { return finish(run); }
}
