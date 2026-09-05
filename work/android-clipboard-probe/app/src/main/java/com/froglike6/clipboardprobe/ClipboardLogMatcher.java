package com.froglike6.clipboardprobe;

public final class ClipboardLogMatcher {
    private static final String DENIAL_PREFIX = "Denying clipboard access to ";

    private ClipboardLogMatcher() {
    }

    public static boolean requestsOverlay(String line, String packageName) {
        return line != null && packageName != null
                && line.contains(DENIAL_PREFIX + packageName);
    }
}
