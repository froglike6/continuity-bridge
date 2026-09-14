package com.froglike6.continuityfixture;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClipboardImageFixtureActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private ImageView preview;
    private String action;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(24, 24, 24, 24);
        status = new TextView(this); status.setTextSize(16); status.setText("이미지 클립보드 QA · PNG/JPEG 원본 바이트 확인"); layout.addView(status);
        for (final String format : new String[] {"png", "jpeg", "oversize", "invalid", "dimensions", "stall"}) {
            Button button = new Button(this); button.setText("이미지 복사: " + format);
            button.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View view) { copy(format); } });
            layout.addView(button);
        }
        Button paste = new Button(this); paste.setText("현재 클립보드 이미지 붙여넣기");
        paste.setOnClickListener(new android.view.View.OnClickListener() { @Override public void onClick(android.view.View view) { paste(); } }); layout.addView(paste);
        preview = new ImageView(this); preview.setAdjustViewBounds(true); layout.addView(preview, new LinearLayout.LayoutParams(-1, 220));
        setContentView(layout); action = getIntent().getStringExtra("image_action");
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused || action == null) return;
        String pending = action; action = null;
        if ("paste".equals(pending)) paste();
        else if ("copy".equals(pending)) copy(getIntent().getStringExtra("image_format"));
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }

    private void copy(final String requested) {
        final String format = requested == null ? "png" : requested;
        if (!java.util.Arrays.asList("png", "jpeg", "oversize", "invalid", "dimensions", "stall").contains(format)) { status.setText("알 수 없는 fixture"); return; }
        status.setText("fixture 이미지 준비 중");
        worker.execute(new Runnable() { @Override public void run() {
            try {
                boolean jpeg = "jpeg".equals(format);
                byte[] bytes = generated(jpeg, "dimensions".equals(format));
                if ("oversize".equals(format)) bytes = java.util.Arrays.copyOf(bytes, 8_388_609);
                if ("invalid".equals(format)) bytes = new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
                final String mime = jpeg ? "image/jpeg" : "image/png";
                String filename = format + (jpeg ? ".jpg" : ".png");
                try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), filename))) { output.write(bytes); }
                final Uri uri = Uri.parse("content://com.froglike6.continuityfixture.images/" + filename);
                final String receipt = receipt("copied", mime, bytes);
                saveReceipt(receipt);
                runOnUiThread(new Runnable() { @Override public void run() {
                    getSystemService(ClipboardManager.class).setPrimaryClip(new ClipData("Image QA", new String[] {mime}, new ClipData.Item(uri)));
                    status.setText(receipt); android.util.Log.i("ContinuityImageFixture", receipt);
                } });
            } catch (final Exception error) { failure(error); }
        } });
    }

    private void paste() {
        final ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
        if (clip == null || clip.getItemCount() != 1 || clip.getItemAt(0).getUri() == null) { status.setText("클립보드에 이미지 URI가 없어요"); return; }
        final Uri uri = clip.getItemAt(0).getUri();
        worker.execute(new Runnable() { @Override public void run() {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new java.io.IOException("missing stream");
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > 8_388_608) throw new java.io.IOException("image exceeds8MiB");
                        output.write(buffer, 0, count);
                    }
                }
                byte[] data = output.toByteArray();
                final String receipt = receipt("pasted", getContentResolver().getType(uri), data); saveReceipt(receipt);
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 4;
                final Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                runOnUiThread(new Runnable() { @Override public void run() {
                    status.setText(receipt); preview.setImageBitmap(image); android.util.Log.i("ContinuityImageFixture", receipt);
                } });
            } catch (final Exception error) { failure(error); }
        } });
    }

    private static byte[] generated(boolean jpeg, boolean excessiveDimensions) {
        Bitmap bitmap = Bitmap.createBitmap(excessiveDimensions ? 8_193 : 256, excessiveDimensions ? 1 : 128, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.rgb(30, 70, 160)); Paint paint = new Paint();
        paint.setColor(Color.rgb(245, 155, 40)); canvas.drawRect(128, 0, 256, 128, paint);
        paint.setColor(Color.WHITE); paint.setTextSize(26); canvas.drawText("IMAGE QA", 24, 72, paint);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bitmap.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, 95, bytes);
        bitmap.recycle(); return bytes.toByteArray();
    }

    private static String receipt(String action, String mime, byte[] bytes) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        StringBuilder hash = new StringBuilder(); for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return "{\"action\":\"" + action + "\",\"mimeType\":\"" + mime + "\",\"bytes\":" + bytes.length
                + ",\"width\":" + bounds.outWidth + ",\"height\":" + bounds.outHeight + ",\"sha256\":\"" + hash + "\"}";
    }

    private void saveReceipt(String receipt) throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "clipboard-image-receipt.json"))) { output.write(receipt.getBytes(StandardCharsets.UTF_8)); }
    }
    private void failure(final Exception error) {
        runOnUiThread(new Runnable() { @Override public void run() { status.setText("이미지 QA 실패: " + error.getClass().getSimpleName()); } });
    }
}
