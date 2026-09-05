package com.froglike6.continuitybridge;

import java.net.URI;
import java.net.URISyntaxException;

public final class ConfigValidator {
    private ConfigValidator() { }

    public static URI httpsUrl(String input) {
        try {
            URI uri = new URI(input);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("invalid_https_url");
            }
            return uri;
        } catch (URISyntaxException error) { throw new IllegalArgumentException("invalid_https_url", error); }
    }

    public static String pin(String input) {
        if (input == null || !input.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid_pin");
        return input;
    }
}
