package com.froglike6.continuitybridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLException;

public final class BridgeEngine {
    public enum Status { CONNECTED, OUTBOX_READY, CLIPBOARD_WAIT, PERMISSION_REQUIRED, AUTH_FAILURE, TLS_FAILURE, SECURITY_FAILURE, PROTOCOL_FAILURE, RETRY, STOPPED }
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
                if (!owner.owns(lease)) return result(Status.STOPPED, null);
                TransportResponse response = transport.publish(token, event);
                if (!owner.owns(lease)) return result(Status.STOPPED, null);
                Result status = httpFailure(response.status()); if (status != null) return status;
                if (response.status() != 200 && response.status() != 201) return result(Status.PROTOCOL_FAILURE, "PublishStatus");
                String serverEpoch = RelayProtocol.published(response.body(), event.eventId());
                state = store.update(new BridgeStateStore.Mutation() {
                    @Override public BridgeState apply(BridgeState current) {
                        BridgeState next = current.published(event.eventId());
                        if (!next.serverEpoch().isEmpty() && !next.serverEpoch().equals(serverEpoch)) return next.relay(serverEpoch, "0");
                        if (next.serverEpoch().isEmpty()) return next.relay(serverEpoch, next.cursor());
                        return next;
                    }
                });
            }
            if (!owner.owns(lease)) return result(Status.STOPPED, null);
            String pollCursor = state.cursor(); String pollEpoch = state.serverEpoch();
            TransportResponse response = transport.poll(token, pollCursor);
            if (!owner.owns(lease)) return result(Status.STOPPED, null);
            Result status = httpFailure(response.status()); if (status != null) return status;
            if (response.status() != 200) return result(Status.PROTOCOL_FAILURE, "PollStatus");
            RelayProtocol.Fetch fetch = RelayProtocol.fetch(response.body(), pollCursor, pollEpoch);
            if (!pollEpoch.isEmpty() && !pollEpoch.equals(fetch.serverEpoch())) {
                store.update(new BridgeStateStore.Mutation() {
                    @Override public BridgeState apply(BridgeState current) { return current.relay(fetch.serverEpoch(), "0"); }
                });
                return result(Status.RETRY, "ServerEpochChanged");
            }
            if (pollEpoch.isEmpty()) {
                state = store.update(new BridgeStateStore.Mutation() {
                    @Override public BridgeState apply(BridgeState current) {
                        return current.serverEpoch().isEmpty() ? current.relay(fetch.serverEpoch(), current.cursor()) : current;
                    }
                });
            }
            for (RelayProtocol.Entry entry : fetch.entries()) {
                if (!owner.owns(lease)) return result(Status.STOPPED, null);
                state = store.load(); ProtocolEvent event = entry.event(); BridgeState.Delivery delivery = state.classify(event);
                if (delivery == BridgeState.Delivery.STALE || delivery == BridgeState.Delivery.CONFLICT) return result(Status.PROTOCOL_FAILURE, delivery.name());
                if (delivery == BridgeState.Delivery.NEW) {
                    if (!applier.apply(event)) return result(owner.owns(lease) ? Status.CLIPBOARD_WAIT : Status.STOPPED, "ApplyUnavailable");
                    state = store.update(new BridgeStateStore.Mutation() {
                        @Override public BridgeState apply(BridgeState current) { return current.applied(event, entry.cursor()); }
                    });
                } else {
                    state = store.update(new BridgeStateStore.Mutation() {
                        @Override public BridgeState apply(BridgeState current) { return current.pendingAck(event.eventId(), entry.cursor()); }
                    });
                }
            }
            state = store.update(new BridgeStateStore.Mutation() {
                @Override public BridgeState apply(BridgeState current) {
                    return fetch.nextCursor().equals(current.cursor()) ? current : current.cursor(fetch.nextCursor());
                }
            });
            Result finalAck = flushAcks(token, state, lease); return finalAck == null ? result(Status.CONNECTED, null) : finalAck;
        } catch (AccessAuthenticationException error) { return result(Status.AUTH_FAILURE, error.getClass().getSimpleName());
        } catch (SecureStoreException error) { return result(Status.SECURITY_FAILURE, error.getClass().getSimpleName());
        } catch (CorruptStateException error) { return result(Status.SECURITY_FAILURE, error.getClass().getSimpleName());
        } catch (PollWakeException error) { return result(owner.owns(lease) ? Status.OUTBOX_READY : Status.STOPPED, null);
        } catch (CancelledTransportException error) { return result(Status.STOPPED, error.getClass().getSimpleName());
        } catch (SSLException error) { return result(Status.TLS_FAILURE, error.getClass().getSimpleName());
        } catch (IllegalArgumentException error) { return result(Status.PROTOCOL_FAILURE, error.getClass().getSimpleName());
        } catch (IOException error) { return result(owner.owns(lease) ? Status.RETRY : Status.STOPPED, error.getClass().getSimpleName());
        } catch (Exception error) { return result(Status.RETRY, error.getClass().getSimpleName()); }
    }

    public void cancel(ConnectionOwner.Lease lease) { owner.cancel(lease); transport.cancel(); }

    private Result flushAcks(String token, BridgeState state, ConnectionOwner.Lease lease) throws Exception {
        if (!owner.owns(lease)) return result(Status.STOPPED, null);
        if (state.pendingAcks().isEmpty()) return null;
        List<String> ids = new ArrayList<>(state.pendingAcks());
        TransportResponse response = transport.acknowledge(token, state.deviceId(), ids);
        if (!owner.owns(lease)) return result(Status.STOPPED, null);
        Result status = httpFailure(response.status()); if (status != null) return status;
        if (response.status() != 200) return result(Status.PROTOCOL_FAILURE, "AckStatus");
        RelayProtocol.acknowledged(response.body(), ids);
        store.update(new BridgeStateStore.Mutation() {
            @Override public BridgeState apply(BridgeState current) {
                return current.acknowledged(new java.util.LinkedHashSet<>(ids));
            }
        }); return null;
    }
    private static Result httpFailure(int status) {
        if (status >= 200 && status < 300) return null;
        if ((status >= 300 && status < 400) || status == 401 || status == 403) return result(Status.AUTH_FAILURE, "Http" + status);
        if (status == 408 || status == 429 || status >= 500) return result(Status.RETRY, "Http" + status);
        return result(Status.PROTOCOL_FAILURE, "Http" + status);
    }
    private static Result result(Status status, String errorClass) { return new Result(status, errorClass); }
}
