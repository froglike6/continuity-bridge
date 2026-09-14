package com.froglike6.continuitybridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ImageClipboardInstrumentationTest extends InstrumentationTestCase {
    public void testOriginalImageBytesRoundTripThroughReadOnlyProvider() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        for (boolean jpeg : new boolean[] {false, true}) {
            byte[] bytes = image(jpeg, 64, 32);
            String mimeType = jpeg ? "image/jpeg" : "image/png";
            ClipboardContent content = ClipboardImageReader.validated(mimeType, bytes);
            Uri uri = ClipboardImageProvider.store(context, content);
            assertEquals(mimeType, context.getContentResolver().getType(uri));
            ClipboardContent reread = ClipboardImageReader.read(context.getContentResolver(), uri, null);
            assertTrue(Arrays.equals(bytes, reread.bytes()));
            try { context.getContentResolver().openFileDescriptor(uri, "w"); fail("image provider accepted writes"); }
            catch (java.io.FileNotFoundException expected) { }
        }
        byte[] largeGeometry = image(false, 8_193, 1);
        try { ClipboardImageReader.validated("image/png", largeGeometry); fail("unsafe image dimensions accepted"); }
        catch (IOException expected) { assertEquals("image_dimensions", expected.getMessage()); }
        try { ClipboardImageReader.validated("image/png", new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}); fail("invalid PNG accepted"); }
        catch (IOException expected) { assertEquals("image_invalid", expected.getMessage()); }
    }

    public void testCaptureReturnsBeforePersistenceAndWakeFinish() throws Exception {
        assertFalse("Stop the bridge before this isolated device test.", ServiceRunCoordinator.process().hasActiveRun());
        final Context context = getInstrumentation().getTargetContext();
        final Activity activity = getInstrumentation().startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        final ClipData[] original = new ClipData[1];
        final BlockingStore store = new BlockingStore();
        final CountDownLatch wake = new CountDownLatch(1);
        final DurableOutbox outbox = new DurableOutbox(store, new UuidIds(), new ObservationWindow(1_000, 32));
        outbox.observeEnqueue(new Runnable() { @Override public void run() { wake.countDown(); } });
        final ClipboardCaptureController controller = new ClipboardCaptureController(context, outbox);
        final CaptureResult[] result = new CaptureResult[1];
        final long[] elapsed = new long[1];
        try {
            getInstrumentation().runOnMainSync(new Runnable() { @Override public void run() {
                original[0] = clipboard.getPrimaryClip();
                clipboard.setPrimaryClip(ClipData.newPlainText("image worker QA", "capture-worker-test"));
                long started = SystemClock.uptimeMillis(); result[0] = controller.capture(clipboard.getPrimaryClip(), "worker-test");
                elapsed[0] = SystemClock.uptimeMillis() - started;
            } });
            assertEquals(CaptureResult.PENDING, result[0]);
            assertTrue("capture blocked the UI for " + elapsed[0] + "ms", elapsed[0] < 250);
            assertTrue("capture worker never reached persistence", store.entered.await(3, TimeUnit.SECONDS));
            final boolean[] uiResponsive = new boolean[1];
            getInstrumentation().runOnMainSync(new Runnable() { @Override public void run() { uiResponsive[0] = true; } });
            assertTrue("UI unavailable during slow persistence", uiResponsive[0]);
            assertEquals("wake must follow persistence", 1L, wake.getCount());
            store.release.countDown();
            assertTrue("capture did not persist and wake", wake.await(3, TimeUnit.SECONDS));
            assertEquals("capture-worker-test", store.value.outbox().get(0).payload().get("text"));
        } finally {
            store.release.countDown();
            controller.stop();
            getInstrumentation().runOnMainSync(new Runnable() { @Override public void run() {
                if (original[0] != null) clipboard.setPrimaryClip(original[0]); else clipboard.clearPrimaryClip();
                activity.finish();
            } });
        }
    }

    private static byte[] image(boolean jpeg, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff3366aa);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, 95, output); bitmap.recycle();
        return output.toByteArray();
    }

    private static final class BlockingStore implements BridgeStateStore {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile BridgeState value = BridgeState.fresh("worker-device", "epoch", 101, 32);
        @Override public BridgeState load() throws IOException {
            if (Looper.myLooper() == Looper.getMainLooper()) throw new AssertionError("state I/O on main looper");
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("blocked_store_timeout"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            return value;
        }
        @Override public void save(BridgeState state) { value = state; }
    }
}
