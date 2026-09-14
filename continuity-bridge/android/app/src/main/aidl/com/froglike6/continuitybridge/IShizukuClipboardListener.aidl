package com.froglike6.continuitybridge;
import android.content.ClipData;
oneway interface IShizukuClipboardListener {
    void onReady();
    void onClip(in ClipData clip);
    void onError(String code);
}
