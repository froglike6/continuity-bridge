package android.net;

public final class NetworkRequest {
    public static final class Builder {
        public Builder addTransportType(int transport) { return this; }
        public NetworkRequest build() { return new NetworkRequest(); }
    }
}
