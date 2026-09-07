package com.froglike6.continuitybridge;

interface ClipboardSurface {
    boolean set(String eventId, String text);
    boolean confirm(String eventId, String text);
}
