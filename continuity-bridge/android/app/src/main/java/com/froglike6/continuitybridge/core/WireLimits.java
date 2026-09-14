package com.froglike6.continuitybridge;

public final class WireLimits {
    public static final int EVENT_BODY_BYTES = 12_582_912;
    public static final int RESPONSE_BODY_BYTES = 12_582_912;
    public static final int IMAGE_DECODED_BYTES = 8_388_608;
    public static final int IMAGE_ENCODED_BYTES = 11_184_812;
    public static final int NOTIFICATION_PAYLOAD_BYTES = 81_920;
    private WireLimits() { }
}
