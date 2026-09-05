package com.froglike6.continuitybridge;

final class ClipboardDenialMatcher {
    private ClipboardDenialMatcher() { }
    static boolean matches(String line, String packageName) {
        if (line == null || packageName == null || packageName.isEmpty()) return false;
        String message = "Denying clipboard access to " + packageName + ",";
        int messageStart = line.indexOf(message);
        if (messageStart < 0 || !line.contains("application is not in focus nor is it a system service")) return false;
        String prefix = line.substring(0, messageStart);
        return prefix.endsWith("ClipboardService: ")
                || prefix.matches("(?:.*?/)?ClipboardService\\(\\s*\\d+\\): ");
    }
}
