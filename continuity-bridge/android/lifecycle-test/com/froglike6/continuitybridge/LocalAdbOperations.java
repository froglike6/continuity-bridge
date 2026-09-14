package com.froglike6.continuitybridge;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class LocalAdbOperations {
    interface Gate { boolean active(); }
    interface Action { void run() throws Exception; }
    static final class NeedsApprovalException extends IOException {
        private static final long serialVersionUID = 1L;
    }
    static final class Start {
        final String nonce;
        final boolean automatic;
        final Gate gate;
        Start(String nonce, boolean automatic, Gate gate) { this.nonce = nonce; this.automatic = automatic; this.gate = gate; }
    }
    final List<Start> starts = new ArrayList<>();
    int pairs;
    int cancellations;
    Action duringStart;
    Action duringPair;
    Gate pairGate;
    LocalAdbOperations(Context context) { }
    void start(String nonce, boolean automatic, Gate gate) throws Exception {
        starts.add(new Start(nonce, automatic, gate));
        Action action = duringStart;
        duringStart = null;
        if (action != null) action.run();
    }
    void pair(int port, String code, Gate gate) throws Exception {
        pairs++;
        pairGate = gate;
        Action action = duringPair;
        duringPair = null;
        if (action != null) action.run();
    }
    void cancel() { cancellations++; }
}
