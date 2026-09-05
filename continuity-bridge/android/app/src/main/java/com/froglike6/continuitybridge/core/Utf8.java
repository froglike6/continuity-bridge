package com.froglike6.continuitybridge;

import java.nio.charset.StandardCharsets;

final class Utf8 {
    private Utf8() { }
    static int size(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    static String truncate(String value, int maximum) {
        String safe = value == null ? "" : value;
        if (size(safe) <= maximum) return safe;
        int low = 0, high = safe.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (size(safe.substring(0, middle)) <= maximum) low = middle; else high = middle - 1;
        }
        while (low > 0 && low < safe.length() && Character.isLowSurrogate(safe.charAt(low))) low--;
        return safe.substring(0, low);
    }
}
