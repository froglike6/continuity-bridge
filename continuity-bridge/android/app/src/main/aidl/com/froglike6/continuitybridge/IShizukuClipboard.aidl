package com.froglike6.continuitybridge;
import android.content.ClipData;
import android.os.ParcelFileDescriptor;
import com.froglike6.continuitybridge.IShizukuClipboardListener;
interface IShizukuClipboard {
    void registerListener(IShizukuClipboardListener listener) = 0;
    void unregisterListener(IShizukuClipboardListener listener) = 1;
    ClipData read() = 2;
    ParcelFileDescriptor openImage(String expectedUri, long expectedTimestamp) = 3;
    void destroy() = 16777114;
}
