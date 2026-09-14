package com.froglike6.continuitybridge;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class ClipboardImageReader {
    static final int MAX_DIMENSION = 8_192;
    static final long MAX_PIXELS = 16_777_216;
    private ClipboardImageReader() { }

    static ClipboardContent read(ContentResolver resolver, Uri uri, String declaredMime) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("image_format");
        String mimeType = resolver.getType(uri);
        if (!"image/png".equals(mimeType) && !"image/jpeg".equals(mimeType)) mimeType = declaredMime;
        if (!"image/png".equals(mimeType) && !"image/jpeg".equals(mimeType)) throw new IOException("image_format");
        byte[] bytes;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("image_unavailable");
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8_192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > ClipboardContent.MAX_IMAGE_BYTES - output.size()) throw new IOException("image_size");
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        return validated(mimeType, bytes);
    }

    static ClipboardContent validated(String mimeType, byte[] bytes) throws IOException {
        final ClipboardContent content;
        try { content = ClipboardContent.image(mimeType, bytes); }
        catch (IllegalArgumentException error) { throw new IOException(error.getMessage(), error); }
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1) throw new IOException("image_invalid");
        if (bounds.outWidth > MAX_DIMENSION || bounds.outHeight > MAX_DIMENSION
                || (long) bounds.outWidth * bounds.outHeight > MAX_PIXELS) throw new IOException("image_dimensions");
        if (!mimeType.equals(bounds.outMimeType)) throw new IOException("image_format");
        BitmapFactory.Options sample = new BitmapFactory.Options(); sample.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample.inSampleSize > 1_024) sample.inSampleSize *= 2;
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, sample);
        if (decoded == null) throw new IOException("image_invalid");
        decoded.recycle();
        return content;
    }

    static String status(Exception error) {
        String reason = error.getMessage();
        if ("image_size".equals(reason)) return "이미지는 8 MiB 이하만 공유할 수 있어요";
        if ("image_dimensions".equals(reason)) return "이미지 해상도가 너무 커요";
        if ("image_format".equals(reason)) return "PNG·JPEG 이미지만 공유할 수 있어요";
        return "이미지를 읽거나 확인하지 못했어요";
    }
}
