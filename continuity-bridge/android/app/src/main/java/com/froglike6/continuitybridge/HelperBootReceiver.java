package com.froglike6.continuitybridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class HelperBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        EmbeddedHelperPreferences preferences = new EmbeddedHelperPreferences(context);
        if (Build.VERSION.SDK_INT < 33 || !preferences.automatic() || !preferences.paired() || !preferences.bridgeRequested()) return;
        try {
            context.startForegroundService(new Intent(context, BridgeService.class));
            android.util.Log.i("EmbeddedClipboard", "boot_restore_requested");
        } catch (RuntimeException error) {
            android.util.Log.w("EmbeddedClipboard", "boot_restore_blocked type=" + error.getClass().getSimpleName());
        }
    }
}
