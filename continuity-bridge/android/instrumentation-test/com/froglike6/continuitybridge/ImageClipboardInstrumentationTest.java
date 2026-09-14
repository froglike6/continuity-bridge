package com.froglike6.continuitybridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

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

    private static byte[] image(boolean jpeg, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff3366aa);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, 95, output); bitmap.recycle();
        return output.toByteArray();
    }


}
