package com.froglike6.clipboardprobe;

public final class ClipboardLogMatcherAssertions {
    private ClipboardLogMatcherAssertions() {
    }

    public static void main(String[] args) {
        String packageName = "com.froglike6.clipboardprobe";

        // Given a real One UI ClipboardService denial for this app
        String ownDenial = "E/ClipboardService: Denying clipboard access to " + packageName
                + ", application is not in focus nor is it a system service for user 0";
        // When the log line is classified, then it requests the focus overlay.
        assert ClipboardLogMatcher.requestsOverlay(ownDenial, packageName);

        // Given the same denial for another app, when classified, then it is ignored.
        assert !ClipboardLogMatcher.requestsOverlay(
                "E/ClipboardService: Denying clipboard access to com.example.other", packageName);

        // Given an unrelated line containing this package, when classified, then it is ignored.
        assert !ClipboardLogMatcher.requestsOverlay(
                "I/ActivityManager: Start proc " + packageName, packageName);

        System.out.println("ClipboardLogMatcher assertions passed");
    }
}
