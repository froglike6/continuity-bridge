package com.froglike6.continuitybridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

public final class ClipboardImageProvider extends ContentProvider {
    private static final String DIRECTORY = "clipboard-images";

    static Uri store(Context context, ClipboardContent content) throws IOException {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("image_store_unavailable");
        String filename = UUID.randomUUID().toString() + ("image/png".equals(content.mimeType()) ? ".png" : ".jpg");
        File target = new File(directory, filename);
        try (FileOutputStream output = new FileOutputStream(target)) { output.write(content.bytes()); output.getFD().sync(); }
        File[] older = directory.listFiles();
        if (older != null) {
            java.util.Arrays.sort(older, new java.util.Comparator<File>() {
                @Override public int compare(File left, File right) { return Long.compare(right.lastModified(), left.lastModified()); }
            });
            int retained = 1;
            for (File file : older) {
                if (file.equals(target) || retained++ < 8) continue;
                context.revokeUriPermission(uri(context, file.getName()), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (!file.delete()) throw new IOException("image_retention_failed");
            }
        }
        return uri(context, filename);
    }

    private static Uri uri(Context context, String filename) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".clipboard.images")
                .appendPath(filename).build();
    }

    private File file(Uri uri) throws FileNotFoundException {
        if (getContext() == null || !"content".equals(uri.getScheme())
                || !(getContext().getPackageName() + ".clipboard.images").equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1 || uri.getQuery() != null || uri.getFragment() != null)
            throw new FileNotFoundException("invalid_image_uri");
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[0-9a-f-]{36}\\.(png|jpg)")) throw new FileNotFoundException("invalid_image_uri");
        File file = new File(new File(getContext().getFilesDir(), DIRECTORY), name);
        if (!file.isFile()) throw new FileNotFoundException("image_unavailable");
        return file;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        try { return file(uri).getName().endsWith(".png") ? "image/png" : "image/jpeg"; }
        catch (FileNotFoundException error) { return null; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("image_read_only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = file(uri); String[] columns = projection == null
                    ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor result = new MatrixCursor(columns, 1); Object[] row = new Object[columns.length];
            for (int index = 0; index < columns.length; index++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) row[index] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[index])) row[index] = file.length();
            }
            result.addRow(row); return result;
        } catch (FileNotFoundException error) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("image_read_only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("image_read_only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("image_read_only"); }
}
