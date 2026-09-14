package android.net.nsd;

public abstract class NsdManager {
    public static final int PROTOCOL_DNS_SD = 1;

    public interface DiscoveryListener {
        void onDiscoveryStarted(String serviceType);
        void onDiscoveryStopped(String serviceType);
        void onStartDiscoveryFailed(String serviceType, int errorCode);
        void onStopDiscoveryFailed(String serviceType, int errorCode);
        void onServiceLost(NsdServiceInfo service);
        void onServiceFound(NsdServiceInfo service);
    }

    public interface ResolveListener {
        void onResolveFailed(NsdServiceInfo service, int errorCode);
        void onServiceResolved(NsdServiceInfo service);
    }

    public abstract void discoverServices(String type, int protocol, DiscoveryListener listener);
    public abstract void stopServiceDiscovery(DiscoveryListener listener);
    public abstract void resolveService(NsdServiceInfo service, ResolveListener listener);
}
