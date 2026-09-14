package com.froglike6.continuitybridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

public final class ClipboardHelperProvider extends ContentProvider {
    static final String AUTHORITY = "com.froglike6.continuitybridge.clipboard.helper";
    private final IBinder owner = new Binder();
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String nonce, Bundle extras) {
        if (Binder.getCallingUid() != 2000) throw new SecurityException("helper_caller_rejected");
        if (!"attach".equals(method) || extras == null) throw new IllegalArgumentException("helper_request_invalid");
        IBinder binder = extras.getBinder("helper");
        if (binder == null) throw new IllegalArgumentException("helper_binder_missing");
        EmbeddedHelperManager manager = EmbeddedHelperManager.get(getContext());
        if (!manager.attach(nonce, binder)) throw new SecurityException("helper_session_rejected");
        Bundle result = new Bundle();
        result.putBinder("owner", owner);
        return result;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw unsupported(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw unsupported(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw unsupported(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw unsupported(); }
    private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("helper_attach_only"); }
}
