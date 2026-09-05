package com.froglike6.continuitybridge;

import java.util.UUID;

final class UuidIds implements EventIds {
    @Override public String next() { return UUID.randomUUID().toString(); }
}
