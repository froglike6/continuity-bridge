package com.froglike6.continuitybridge;

interface ClipboardSurface {
    boolean set(String eventId, String text);
    boolean confirm(String eventId, String text);
    default boolean setContent(String eventId, ClipboardContent content) {
        return !content.isImage() && set(eventId, content.text());
    }
    default boolean confirmContent(String eventId, ClipboardContent content) {
        return !content.isImage() && confirm(eventId, content.text());
    }
}
