package com.froglike6.continuitybridge;

import android.os.IBinder;
import android.os.RemoteException;

interface IShizukuClipboard {
    void destroy() throws RemoteException;
    final class Stub {
        private Stub() { }
        static IShizukuClipboard asInterface(IBinder value) { return (IShizukuClipboard) value; }
    }
}
