package com.froglike6.continuitybridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ImageClipboardSuite {
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1sAAAAASUVORK5CYII=");
    private static int cases;
    private ImageClipboardSuite() { }

    public static void main(String[] args) throws Exception {
        // Given: an image copied after text while offline.
        Path directory = Files.createTempDirectory("image-clipboard-");
        try {
            FileBridgeStateStore store = FileBridgeStateStore.create(directory.resolve("state"), TestStateCipher.create(), "device", "epoch", 101, 32);
            DurableOutbox outbox = new DurableOutbox(store, () -> "image-" + System.nanoTime(), new ObservationWindow(1_000, 32));
            check(outbox.captureClipboard("old text", null, "timestamp:1", 1) == CaptureResult.ENQUEUED, "text enqueued");
            ClipboardContent image = ClipboardContent.image("image/png", PNG);
            // When: image capture persists and the process reloads its outbox.
            check(outbox.captureContent(image, null, "timestamp:2", 2) == CaptureResult.ENQUEUED, "image enqueued");
            BridgeState loaded = store.load();
            // Then: the newest clipboard only is retained, with original bytes and MIME.
            check(loaded.outbox().size() == 1 && "clipboard.image".equals(loaded.outbox().get(0).kind()), "image replaces text");
            check(image.payload().equals(loaded.outbox().get(0).payload()), "image round trip bytes");
            check(outbox.captureClipboard("new text", null, "timestamp:3", 3) == CaptureResult.ENQUEUED
                    && "clipboard.text".equals(store.load().outbox().get(0).kind()), "text replaces image");
            imageApplyPersistsBeforeWrite(store, image);
            imageEchoAfterAsyncConfirmation(store, outbox, image);
            invalidImages();
            // Given: a full size image payload, which exceeds the old state file cap after base64 wrapping.
            byte[] maximum = java.util.Arrays.copyOf(PNG, 8_388_608);
            // When / Then: storing the legal wire size succeeds without notification eviction of the clipboard.
            check(outbox.captureContent(ClipboardContent.image("image/png", maximum), null, "timestamp:8", 8)
                    == CaptureResult.ENQUEUED && store.load().outbox().size() == 1, "8MiB image durable round trip");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
                for (Path file : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(file);
            }
        }
        System.out.println("IMAGE_CLIPBOARD_HOST_OK cases=" + cases);
    }

    private static void imageApplyPersistsBeforeWrite(BridgeStateStore store, ClipboardContent image) throws Exception {
        ProtocolEvent event = new ProtocolEvent("remote-image", "mac", "macos", "mac-epoch", 1,
                "clipboard.image", 4, null, image.payload());
        ClipboardApplyTransaction transaction = new ClipboardApplyTransaction(store, new ClipboardSurface() {
            @Override public boolean set(String eventId, String text) { throw new AssertionError("image routed as text"); }
            @Override public boolean confirm(String eventId, String text) { throw new AssertionError("image confirmed as text"); }
            @Override public boolean setContent(String eventId, ClipboardContent content) {
                try { check(eventId.equals(store.load().remoteApplyId()), "apply intent before image write"); }
                catch (java.io.IOException error) { throw new AssertionError(error); }
                return true;
            }
            @Override public boolean confirmContent(String eventId, ClipboardContent content) {
                return RemoteApplyTracker.confirmContent(eventId, content, "timestamp:4");
            }
        });
        // When: the platform accepts and confirms the image.
        check(transaction.apply(event), "image apply confirmed");
        // Then: the observation identity is durable before the caller can ACK.
        check("timestamp:4".equals(store.load().remoteApplyObservationIdentity()), "image receipt persisted");
    }

    private static void imageEchoAfterAsyncConfirmation(BridgeStateStore store, DurableOutbox outbox, ClipboardContent image) throws Exception {
        // Given: capture observes a remote image before asynchronous persistence runs.
        RemoteApplyTracker.beginContent("remote-image", image);
        RemoteApplyTracker.Observation observation = RemoteApplyTracker.observe("remote-image", "timestamp:4");
        RemoteApplyTracker.confirmContent("remote-image", image, "timestamp:4");
        RemoteApplyTracker.clear("remote-image");
        // When: capture resumes after the remote transaction has completed.
        CaptureResult result = outbox.captureContent(image, "remote-image", "timestamp:4", 4, observation);
        // Then: it remains a remote echo; a later stable observation still represents a fresh copy.
        check(result == CaptureResult.REMOTE_SKIPPED, "async image echo suppressed");
        check(outbox.captureContent(image, "remote-image", "timestamp:5", 5) == CaptureResult.ENQUEUED,
                "later independent image copy emitted");
    }

    private static void invalidImages() {
        expectInvalid(() -> ClipboardContent.image("image/jpeg", PNG), "MIME signature mismatch");
        expectInvalid(() -> ClipboardContent.image("image/png", new byte[8_388_609]), "oversize image");
        expectInvalid(() -> ClipboardContent.image("image/gif", PNG), "unsupported format");
        Map<String, String> payload = new LinkedHashMap<>(); payload.put("mimeType", "image/png"); payload.put("dataBase64", "***");
        expectInvalid(() -> ClipboardContent.fromEvent(new ProtocolEvent("bad", "mac", "macos", "epoch", 1,
                "clipboard.image", 1, null, payload)), "invalid base64");
    }

    private static void expectInvalid(Runnable action, String name) {
        try { action.run(); throw new AssertionError(name); } catch (IllegalArgumentException expected) { cases++; }
    }
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); cases++; }
}
