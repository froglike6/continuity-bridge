package com.froglike6.continuityfixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public final class ClipboardImageFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (getContext() == null || !"com.froglike6.continuityfixture.images".equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1 || name == null || !name.matches("(png|jpeg|oversize|invalid|dimensions|stall)\\.(png|jpg)"))
            throw new FileNotFoundException("unknown_fixture");
        return new File(getContext().getFilesDir(), name);
    }
    @Override public String getType(Uri uri) {
        try { return file(uri).getName().endsWith(".jpg") ? "image/jpeg" : "image/png"; }
        catch (FileNotFoundException error) { return null; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read_only");
        File image = file(uri);
        if ("stall.png".equals(image.getName())) {
            android.util.Log.i("ContinuityImageFixture", "{\"phase\":\"stall_open_started\",\"delayMs\":30000}");
            android.os.SystemClock.sleep(30_000L);
            android.util.Log.i("ContinuityImageFixture", "{\"phase\":\"stall_open_finished\"}");
        }
        return ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = file(uri); MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[] {file.getName(), file.length()}); return cursor;
        } catch (FileNotFoundException error) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read_only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("read_only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("read_only"); }
}
