package com.froglike6.continuitybridge;

final class ClipboardDenialMatcher {
    private ClipboardDenialMatcher() { }
    static boolean matches(String line, String packageName) {
        if (line == null || packageName == null || packageName.isEmpty()) return false;
        String exact = "ClipboardService: Denying clipboard access to " + packageName + ",";
        return line.contains(exact) && line.contains("application is not in focus nor is it a system service");
    }
}
