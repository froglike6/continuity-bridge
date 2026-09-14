package android.net.nsd;

import java.net.InetAddress;

public final class NsdServiceInfo {
    private final InetAddress host;
    private final int port;

    public NsdServiceInfo(InetAddress host, int port) {
        this.host = host;
        this.port = port;
    }

    public InetAddress getHost() { return host; }
    public int getPort() { return port; }
}
