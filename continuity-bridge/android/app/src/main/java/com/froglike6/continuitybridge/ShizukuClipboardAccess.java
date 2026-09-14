package com.froglike6.continuitybridge;

import android.content.ClipData;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

final class ShizukuClipboardAccess {
    private static final String LISTENER = "android.content.IOnPrimaryClipChangedListener";
    private final Object clipboard;
    private final Class<?> contract;
    private final int userId;
    private Object listener;

    ShizukuClipboardAccess(int userId) throws ReflectiveOperationException {
        this.userId = userId;
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "clipboard");
        if (binder == null) throw new IllegalStateException("clipboard_service_missing");
        contract = Class.forName("android.content.IClipboard");
        clipboard = Class.forName("android.content.IClipboard$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    ClipData read() throws ReflectiveOperationException {
        return (ClipData) invoke("getPrimaryClip", null);
    }

    void register(final Runnable changed) throws ReflectiveOperationException {
        if (listener != null) return;
        final Binder callback = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    if (reply != null) reply.writeString(LISTENER);
                    return true;
                }
                if (code == FIRST_CALL_TRANSACTION) {
                    data.enforceInterface(LISTENER);
                    changed.run();
                    if (reply != null) reply.writeNoException();
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        Object candidate = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Class.forName(LISTENER)}, new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] args) {
                        switch (method.getName()) {
                            case "asBinder": return callback;
                            case "dispatchPrimaryClipChanged": changed.run(); return null;
                            case "toString": return "ShizukuClipboardListener";
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    }
                });
        invoke("addPrimaryClipChangedListener", candidate);
        listener = candidate;
    }

    void unregister() throws ReflectiveOperationException {
        Object previous = listener;
        listener = null;
        if (previous != null) invoke("removePrimaryClipChangedListener", previous);
    }

    private Object invoke(String name, Object callback) throws ReflectiveOperationException {
        for (Method method : contract.getMethods()) {
            if (!name.equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            int offset = callback == null ? 0 : 1;
            int tail = types.length - offset;
            if (tail < 2 || tail > 4 || types[offset] != String.class) continue;
            Object[] args = new Object[types.length];
            if (callback != null) args[0] = callback;
            args[offset] = "com.android.shell";
            int integerCount = 0;
            boolean supported = true;
            for (int i = offset + 1; i < types.length; i++) {
                if (types[i] == String.class) args[i] = null;
                else if (types[i] == int.class) args[i] = integerCount++ == 0 ? userId : 0;
                else supported = false;
            }
            if (supported && integerCount > 0) return method.invoke(clipboard, args);
        }
        throw new NoSuchMethodException("unsupported_clipboard_contract:" + name);
    }

    static String errorCode(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
        return cause instanceof SecurityException ? "clipboard_permission" : "clipboard_unavailable";
    }
}
