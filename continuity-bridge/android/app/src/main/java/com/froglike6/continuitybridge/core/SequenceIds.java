package com.froglike6.continuitybridge;

final class SequenceIds implements EventIds {
    private final String prefix; private long next = 1;
    SequenceIds(String prefix) { this.prefix = prefix; }
    @Override public synchronized String next() { return prefix + "-" + next++; }
}
