package android.net;

public final class ConnectivityManager {
    public abstract static class NetworkCallback { public void onAvailable(Network network) { } }
    public boolean wifi = true;
    public void registerNetworkCallback(NetworkRequest request, NetworkCallback callback) { }
    public Network[] getAllNetworks() { return wifi ? new Network[] { new Network() } : new Network[0]; }
    public NetworkCapabilities getNetworkCapabilities(Network network) { return new NetworkCapabilities(); }
}
