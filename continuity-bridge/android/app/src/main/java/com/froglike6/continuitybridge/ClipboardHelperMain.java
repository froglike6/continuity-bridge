package com.froglike6.continuitybridge;

import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

public final class ClipboardHelperMain {
    private ClipboardHelperMain() { }
    public static void main(String[] args) {
        if (Process.myUid() != 2000 || args.length != 1 || !args[0].matches("[0-9a-f]{64}")) System.exit(1);
        String stage = "context";
        try {
            Looper.prepareMainLooper();
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("systemMain").invoke(null);
            Context system = (Context) type.getMethod("getSystemContext").invoke(thread);
            Context shell = system.createPackageContext("com.android.shell", 0);
            stage = "clipboard_service";
            ShizukuClipboardService helper = new ShizukuClipboardService(shell);
            Bundle extras = new Bundle();
            extras.putBinder("helper", helper);
            stage = "provider_attach";
            Bundle response = attach(args[0], extras);
            IBinder owner = response == null ? null : response.getBinder("owner");
            if (owner == null) throw new IllegalStateException("helper_owner_missing");
            owner.linkToDeath(new IBinder.DeathRecipient() {
                @Override public void binderDied() { System.exit(0); }
            }, 0);
            android.util.Log.i("EmbeddedClipboard", "helper_attached uid=" + Process.myUid());
            Looper.loop();
        } catch (Exception error) {
            android.util.Log.e("EmbeddedClipboard", "helper_start_failed stage=" + stage
                    + " type=" + error.getClass().getSimpleName() + " site=" + error.getStackTrace()[0]);
            System.exit(1);
        }
    }

    private static Bundle attach(String nonce, Bundle extras) throws ReflectiveOperationException {
        String authority = ClipboardHelperProvider.AUTHORITY;
        Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Class<?> managerType = Class.forName("android.app.IActivityManager");
        IBinder token = new Binder();
        Object holder = managerType.getMethod("getContentProviderExternal", String.class, int.class,
                IBinder.class, String.class).invoke(manager, authority, 0, token, "continuity_helper");
        if (holder == null) throw new IllegalStateException("helper_provider_missing");
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            Class<?> contract = Class.forName("android.content.IContentProvider");
            if (Build.VERSION.SDK_INT >= 31) {
                Class<?> source = Class.forName("android.content.AttributionSource");
                Class<?> builder = Class.forName("android.content.AttributionSource$Builder");
                Object value = builder.getConstructor(int.class).newInstance(Process.myUid());
                builder.getMethod("setPackageName", String.class).invoke(value, "com.android.shell");
                Object attribution = builder.getMethod("build").invoke(value);
                return (Bundle) contract.getMethod("call", source, String.class, String.class, String.class, Bundle.class)
                        .invoke(provider, attribution, authority, "attach", nonce, extras);
            }
            return (Bundle) contract.getMethod("call", String.class, String.class, String.class, String.class,
                    String.class, Bundle.class).invoke(provider, "com.android.shell", null, authority, "attach", nonce, extras);
        } finally {
            managerType.getMethod("removeContentProviderExternalAsUser", String.class, IBinder.class, int.class)
                    .invoke(manager, authority, token, 0);
        }
    }
}
