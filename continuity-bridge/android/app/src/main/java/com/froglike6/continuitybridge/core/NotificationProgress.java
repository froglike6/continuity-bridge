package com.froglike6.continuitybridge;

final class NotificationProgress {
    private final int value, max;
    private final boolean indeterminate;

    NotificationProgress(int value, int max, boolean indeterminate) {
        if (value < 0 || max < 0 || value > max || (max == 0 && !indeterminate))
            throw new IllegalArgumentException("invalid notification progress");
        this.value = value; this.max = max; this.indeterminate = indeterminate;
    }

    int getValue() { return value; }
    int getMax() { return max; }
    boolean isIndeterminate() { return indeterminate; }
}
