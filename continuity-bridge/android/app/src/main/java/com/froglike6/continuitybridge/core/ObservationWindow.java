package com.froglike6.continuitybridge;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class ObservationWindow {
    private final long windowMs;
    private final int limit;
    private final LinkedHashMap<String, Long> observed = new LinkedHashMap<>();

    ObservationWindow(long windowMs, int limit) {
        if (windowMs < 1 || limit < 1) throw new IllegalArgumentException("invalid_observation_window");
        this.windowMs = windowMs; this.limit = limit;
    }

    synchronized boolean canAccept(String identity, long nowMs) {
        if (identity == null || identity.isEmpty() || nowMs < 0) return false;
        Long previous = observed.get(identity);
        return previous == null || nowMs - previous > windowMs;
    }

    synchronized boolean accept(String identity, long nowMs) {
        if (!canAccept(identity, nowMs)) return false;
        Iterator<Map.Entry<String, Long>> entries = observed.entrySet().iterator();
        while (entries.hasNext()) if (nowMs - entries.next().getValue() > windowMs) entries.remove();
        observed.remove(identity); observed.put(identity, nowMs);
        while (observed.size() > limit) observed.remove(observed.keySet().iterator().next());
        return true;
    }
}
