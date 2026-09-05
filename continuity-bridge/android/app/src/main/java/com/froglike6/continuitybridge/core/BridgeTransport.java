package com.froglike6.continuitybridge;

import java.io.IOException;
import java.util.List;

public interface BridgeTransport {
    TransportResponse publish(String token, ProtocolEvent event) throws Exception;
    TransportResponse poll(String token, String cursor) throws Exception;
    TransportResponse acknowledge(String token, String deviceId, List<String> eventIds) throws Exception;
    void cancel();
}
