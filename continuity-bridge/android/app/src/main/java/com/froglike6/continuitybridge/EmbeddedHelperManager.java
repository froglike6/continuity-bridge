package com.froglike6.continuitybridge;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.github.muntashirakon.adb.AdbAuthenticationFailedException;
import io.github.muntashirakon.adb.AdbPairingRequiredException;

final class EmbeddedHelperManager {
    enum State { UNPAIRED, PAIRING, STARTING, READY, WIFI_REQUIRED, DEBUGGING_REQUIRED, PAIRING_REQUIRED, ERROR, STOPPED, UNSUPPORTED }
    interface Observer { void onState(State state); }
    private static EmbeddedHelperManager instance;
    private final Context context;
    private final EmbeddedHelperPreferences preferences;
    private final LocalAdbOperations adb;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Observer> observers = new ArrayList<>();
    private volatile State state;
    private volatile IBinder binder;
    private volatile String expectedNonce;
    private boolean busy;
    private volatile boolean wanted;
    private volatile boolean requestAutomatic;
    private boolean automaticAttempt;
    private volatile int generation;
    private final Runnable retry = new Runnable() { @Override public void run() { if (wanted) startOnMain(); } };

    static synchronized EmbeddedHelperManager get(Context context) {
        if (instance == null) instance = new EmbeddedHelperManager(context.getApplicationContext());
        return instance;
    }
    private EmbeddedHelperManager(Context context) {
        this.context = context;
        preferences = new EmbeddedHelperPreferences(context);
        adb = new LocalAdbOperations(context);
        state = Build.VERSION.SDK_INT < 30 ? State.UNSUPPORTED : preferences.paired() ? State.STOPPED : State.UNPAIRED;
        ConnectivityManager network = context.getSystemService(ConnectivityManager.class);
        if (network != null) network.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network value) {
                main.post(new Runnable() { @Override public void run() {
                    if (wanted && (state == State.WIFI_REQUIRED || state == State.DEBUGGING_REQUIRED)) startOnMain();
                }});
            }
        });
    }
    State state() { return state; }
    boolean paired() { return preferences.paired(); }
    boolean automaticRecovery() { return Build.VERSION.SDK_INT >= 33 && (requestAutomatic || preferences.automatic()); }
    IBinder binder() { return binder; }
    void addObserver(final Observer observer) { main.post(new Runnable() { @Override public void run() {
        if (!observers.contains(observer)) observers.add(observer);
        observer.onState(state);
    }}); }
    void removeObserver(final Observer observer) { main.post(new Runnable() { @Override public void run() { observers.remove(observer); }}); }
    void start() { main.post(new Runnable() { @Override public void run() { wanted = true; startOnMain(); }}); }
    void restart() { main.post(new Runnable() { @Override public void run() { cancelAttempt(); release(); wanted = true; startOnMain(); }}); }
    void onUserStop() { main.post(new Runnable() { @Override public void run() {
        preferences.bridgeRequested(false);
        wanted = false;
        requestAutomatic = false;
        cancelAttempt();
        release();
        setState(State.STOPPED);
    }}); }
    void setAutomaticRecovery(final boolean enabled) { main.post(new Runnable() { @Override public void run() {
        if (!enabled || Build.VERSION.SDK_INT < 33) {
            requestAutomatic = false;
            preferences.automatic(false);
            if (automaticAttempt) { cancelAttempt(); if (wanted) startOnMain(); }
            setState(state);
            return;
        }
        requestAutomatic = true;
        wanted = true;
        startOnMain();
    }}); }

    void pair(final int port, final String code, final boolean automatic) { main.post(new Runnable() { @Override public void run() {
        if (busy || Build.VERSION.SDK_INT < 30) return;
        busy = true;
        wanted = true;
        requestAutomatic = automatic && Build.VERSION.SDK_INT >= 33;
        if (!requestAutomatic) preferences.automatic(false);
        automaticAttempt = false;
        main.removeCallbacks(retry);
        final int attempt = beginAttempt(null);
        final LocalAdbOperations.Gate gate = gate(attempt);
        setState(State.PAIRING);
        io.execute(new Runnable() { @Override public void run() {
            try {
                adb.pair(port, code, gate);
                main.post(new Runnable() { @Override public void run() {
                    if (!completeAttempt(attempt)) return;
                    preferences.paired(true);
                    startOnMain();
                }});
            } catch (final Exception error) { main.post(new Runnable() { @Override public void run() {
                if (!completeAttempt(attempt)) return;
                android.util.Log.w("EmbeddedClipboard", "pairing_failed type=" + error.getClass().getSimpleName());
                setState(State.PAIRING_REQUIRED);
            }}); }
        }});
    }}); }

    private void startOnMain() {
        main.removeCallbacks(retry);
        if (!wanted || busy) return;
        if (Build.VERSION.SDK_INT < 30) { setState(State.UNSUPPORTED); return; }
        if (binder != null && binder.isBinderAlive() && !requestAutomatic) { setState(State.READY); return; }
        if (!preferences.paired()) { setState(State.UNPAIRED); return; }
        if (!wifiAvailable()) { setState(State.WIFI_REQUIRED); scheduleRetry(); return; }
        final boolean automatic = automaticRecovery();
        final String nonce = nonce();
        final int attempt = beginAttempt(nonce);
        final LocalAdbOperations.Gate gate = gate(attempt);
        automaticAttempt = automatic;
        busy = true;
        setState(State.STARTING);
        io.execute(new Runnable() { @Override public void run() {
            try {
                adb.start(nonce, automatic, gate);
                main.post(new Runnable() { @Override public void run() {
                    if (!completeAttempt(attempt)) return;
                    if (automatic) { preferences.automatic(true); requestAutomatic = false; }
                    if (requestAutomatic) { startOnMain(); return; }
                    if (binder != null && binder.isBinderAlive()) setState(State.READY);
                    else main.postDelayed(new Runnable() { @Override public void run() {
                        if (generation == attempt && binder == null) { expectedNonce = null; setState(State.ERROR); scheduleRetry(); }
                    }}, 8000);
                }});
            } catch (final Exception error) { main.post(new Runnable() { @Override public void run() {
                if (!completeAttempt(attempt)) return;
                expectedNonce = null;
                android.util.Log.w("EmbeddedClipboard", "startup_failed type=" + error.getClass().getSimpleName());
                if (error instanceof AdbPairingRequiredException || error instanceof AdbAuthenticationFailedException)
                    setState(State.PAIRING_REQUIRED);
                else if (error instanceof LocalAdbOperations.NeedsApprovalException) setState(State.DEBUGGING_REQUIRED);
                else { setState(wifiAvailable() ? State.DEBUGGING_REQUIRED : State.WIFI_REQUIRED); scheduleRetry(); }
            }}); }
        }});
    }

    synchronized boolean attach(String nonce, final IBinder value) {
        String expected = expectedNonce;
        if (!wanted || expected == null || nonce == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                nonce.getBytes(StandardCharsets.US_ASCII))) return false;
        expectedNonce = null;
        final int attempt = generation;
        main.post(new Runnable() { @Override public void run() {
            if (!wanted || generation != attempt) { destroy(value); return; }
            release();
            binder = value;
            try { value.linkToDeath(new IBinder.DeathRecipient() { @Override public void binderDied() {
                main.post(new Runnable() { @Override public void run() {
                    if (binder != value) return;
                    binder = null;
                    setState(State.STOPPED);
                    if (wanted) main.postDelayed(retry, 1000);
                }});
            }}, 0); }
            catch (RemoteException gone) { binder = null; setState(State.ERROR); scheduleRetry(); return; }
            setState(State.READY);
        }});
        return true;
    }
    private synchronized int beginAttempt(String nonce) { generation++; expectedNonce = nonce; return generation; }
    private LocalAdbOperations.Gate gate(final int attempt) { return new LocalAdbOperations.Gate() {
        @Override public boolean active() { return wanted && generation == attempt; }
    }; }
    private void cancelAttempt() {
        beginAttempt(null);
        automaticAttempt = false;
        main.removeCallbacks(retry);
        adb.cancel();
    }
    private boolean completeAttempt(int attempt) {
        busy = false;
        if (generation != attempt || !wanted) { if (wanted) startOnMain(); return false; }
        return true;
    }
    private void release() { IBinder previous = binder; binder = null; if (previous != null) destroy(previous); }
    private void destroy(final IBinder value) { io.execute(new Runnable() { @Override public void run() {
        try { IShizukuClipboard.Stub.asInterface(value).destroy(); }
        catch (RemoteException | RuntimeException gone) { android.util.Log.i("EmbeddedClipboard", "helper_already_stopped"); }
    }}); }
    private void scheduleRetry() { if (wanted) main.postDelayed(retry, 30_000); }
    private void setState(State value) {
        state = value;
        android.util.Log.i("EmbeddedClipboard", "state=" + value.name());
        for (Observer observer : new ArrayList<Observer>(observers)) observer.onState(value);
    }
    private boolean wifiAvailable() {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return false;
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
        }
        return false;
    }
    private static String nonce() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder(64);
        for (byte part : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
        return value.toString();
    }
}
