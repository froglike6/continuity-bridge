package com.froglike6.continuitybridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLException;

public final class BridgeEngine {
    public enum Status { CONNECTED, PERMISSION_REQUIRED, AUTH_FAILURE, TLS_FAILURE, SECURITY_FAILURE, PROTOCOL_FAILURE, RETRY, STOPPED }
    public static final class Result {
        private final Status status; private final String errorClass;
        Result(Status status, String errorClass) { this.status = status; this.errorClass = errorClass; }
        public Status status() { return status; }
        public String errorClass() { return errorClass; }
    }

    private final BridgeStateStore store;
    private final BridgeTransport transport;
    private final TokenProvider tokens;
    private final EventApplier applier;
    private final ConnectionOwner owner;

    public BridgeEngine(BridgeStateStore store, BridgeTransport transport, TokenProvider tokens, EventApplier applier, ConnectionOwner owner) {
        this.store = store; this.transport = transport; this.tokens = tokens; this.applier = applier; this.owner = owner;
    }

    public Result step(ConnectionOwner.Lease lease) {
        if (!owner.owns(lease)) return result(Status.STOPPED, null);
        try {
            String token = tokens.load();
            if (token == null || token.isEmpty()) return result(Status.AUTH_FAILURE, "MissingCredential");
            BridgeState state = store.load();
            Result ackResult = flushAcks(token, state, lease);
            if (ackResult != null) return ackResult;
            state = store.load();
            for (ProtocolEvent event : state.outbox()) {
                TransportResponse response = transport.publish(token, event);
                if (!owner.owns(lease)) return result(Status.STOPPED, null);
                Result status = httpFailure(response.status()); if (status != null) return status;
                if (response.status() != 200 && response.status() != 201) return result(Status.PROTOCOL_FAILURE, "PublishStatus");
                String serverEpoch = RelayProtocol.published(response.body(), event.eventId());
                state = store.load();
                state = state.published(event.eventId());
                if (!state.serverEpoch().isEmpty() && !state.serverEpoch().equals(serverEpoch)) state = state.relay(serverEpoch, "0");
                else if (state.serverEpoch().isEmpty()) state = state.relay(serverEpoch, state.cursor());
                store.save(state);
            }
            TransportResponse response = transport.poll(token, state.cursor());
            if (!owner.owns(lease)) return result(Status.STOPPED, null);
            Result status = httpFailure(response.status()); if (status != null) return status;
            if (response.status() != 200) return result(Status.PROTOCOL_FAILURE, "PollStatus");
            RelayProtocol.Fetch fetch = RelayProtocol.fetch(response.body(), state.cursor(), state.serverEpoch());
            if (!state.serverEpoch().isEmpty() && !state.serverEpoch().equals(fetch.serverEpoch())) {
                state = store.load();
                store.save(state.relay(fetch.serverEpoch(), "0")); return result(Status.RETRY, "ServerEpochChanged");
            }
            if (state.serverEpoch().isEmpty()) { state = state.relay(fetch.serverEpoch(), state.cursor()); store.save(state); }
            for (RelayProtocol.Entry entry : fetch.entries()) {
                state = store.load(); ProtocolEvent event = entry.event(); BridgeState.Delivery delivery = state.classify(event);
                if (delivery == BridgeState.Delivery.STALE || delivery == BridgeState.Delivery.CONFLICT) return result(Status.PROTOCOL_FAILURE, delivery.name());
                if (delivery == BridgeState.Delivery.NEW) {
                    if (!applier.apply(event)) return result(Status.PERMISSION_REQUIRED, "ApplyUnavailable");
                    state = store.load();
                    state = state.applied(event, entry.cursor());
                } else { state = state.pendingAck(event.eventId(), entry.cursor()); }
                store.save(state);
            }
            state = store.load();
            if (!fetch.nextCursor().equals(state.cursor())) { state = state.cursor(fetch.nextCursor()); store.save(state); }
            Result finalAck = flushAcks(token, state, lease); return finalAck == null ? result(Status.CONNECTED, null) : finalAck;
        } catch (SecureStoreException error) { return result(Status.SECURITY_FAILURE, error.getClass().getSimpleName());
        } catch (CorruptStateException error) { return result(Status.SECURITY_FAILURE, error.getClass().getSimpleName());
        } catch (CancelledTransportException error) { return result(Status.STOPPED, error.getClass().getSimpleName());
        } catch (SSLException error) { return result(Status.TLS_FAILURE, error.getClass().getSimpleName());
        } catch (IllegalArgumentException error) { return result(Status.PROTOCOL_FAILURE, error.getClass().getSimpleName());
        } catch (IOException error) { return result(owner.owns(lease) ? Status.RETRY : Status.STOPPED, error.getClass().getSimpleName());
        } catch (Exception error) { return result(Status.RETRY, error.getClass().getSimpleName()); }
    }

    public void cancel(ConnectionOwner.Lease lease) { owner.cancel(lease); transport.cancel(); }

    private Result flushAcks(String token, BridgeState state, ConnectionOwner.Lease lease) throws Exception {
        if (state.pendingAcks().isEmpty()) return null;
        List<String> ids = new ArrayList<>(state.pendingAcks());
        TransportResponse response = transport.acknowledge(token, state.deviceId(), ids);
        if (!owner.owns(lease)) return result(Status.STOPPED, null);
        Result status = httpFailure(response.status()); if (status != null) return status;
        if (response.status() != 200) return result(Status.PROTOCOL_FAILURE, "AckStatus");
        RelayProtocol.acknowledged(response.body(), ids); state = store.load();
        store.save(state.acknowledged(new java.util.LinkedHashSet<>(ids))); return null;
    }
    private static Result httpFailure(int status) {
        if (status >= 200 && status < 300) return null;
        if (status == 401 || status == 403) return result(Status.AUTH_FAILURE, "Http" + status);
        if (status == 408 || status == 429 || status >= 500) return result(Status.RETRY, "Http" + status);
        return result(Status.PROTOCOL_FAILURE, "Http" + status);
    }
    private static Result result(Status status, String errorClass) { return new Result(status, errorClass); }
}
