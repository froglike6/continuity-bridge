package android.os;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class Handler {
    private static final ArrayDeque<Runnable> READY = new ArrayDeque<>();
    private static final List<Runnable> DELAYED = new ArrayList<>();
    public Handler(Looper looper) { }
    public boolean post(Runnable task) { READY.add(task); return true; }
    public boolean postDelayed(Runnable task, long delay) { DELAYED.add(task); return true; }
    public void removeCallbacks(Runnable task) { READY.removeIf(value -> value == task); DELAYED.removeIf(value -> value == task); }
    public static void drain() { while (!READY.isEmpty()) READY.remove().run(); }
    public static int delayedCount() { return DELAYED.size(); }
    public static void reset() { READY.clear(); DELAYED.clear(); }
}
