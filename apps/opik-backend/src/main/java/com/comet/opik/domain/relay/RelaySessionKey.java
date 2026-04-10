package com.comet.opik.domain.relay;

import java.util.UUID;

final class RelaySessionKey {

    private static final String PREFIX = "opik:relay:";

    private RelaySessionKey() {
    }

    static String key(UUID sessionId) {
        return PREFIX + sessionId;
    }
}
