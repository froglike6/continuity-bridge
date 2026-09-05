package com.froglike6.continuitybridge;

import java.util.concurrent.atomic.AtomicLong;

public final class ConnectionOwner {
    public static final class Lease {
        private final long generation;
        private volatile boolean cancelled;
        Lease(long generation) { this.generation = generation; }
        public boolean cancelled() { return cancelled; }
        public long generation() { return generation; }
        void cancel() { cancelled = true; }
    }

    private final AtomicLong generations = new AtomicLong();
    private Lease active;

    public synchronized Lease start() {
        if (active != null) active.cancel();
        active = new Lease(generations.incrementAndGet());
        return active;
    }

    public synchronized void cancel(Lease lease) {
        if (lease == active) { lease.cancel(); active = null; }
    }

    public synchronized boolean owns(Lease lease) { return lease == active && !lease.cancelled(); }
}
