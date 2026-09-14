package com.froglike6.continuitybridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import java.io.ByteArrayOutputStream;

final class NotificationAppIcon {
    private NotificationAppIcon() { }

    static String encode(Drawable drawable) {
        if (drawable == null) return null;
        Rect originalBounds = new Rect(drawable.getBounds());
        try {
            for (int size : new int[] { 64, 48, 32 }) {
                Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                try {
                    drawable.setBounds(0, 0, size, size);
                    drawable.draw(new Canvas(bitmap));
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) && output.size() <= 12_288)
                        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
                } finally { bitmap.recycle(); }
            }
        } finally { drawable.setBounds(originalBounds); }
        return null;
    }
}
