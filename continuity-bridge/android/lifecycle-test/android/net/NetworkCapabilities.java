package android.net;

public final class NetworkCapabilities {
    public static final int TRANSPORT_WIFI = 1;
    public boolean hasTransport(int transport) { return transport == TRANSPORT_WIFI; }
}
