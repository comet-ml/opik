package com.comet.opik.domain.connect;

import java.util.UUID;

final class OpikConnectSessionKey {

    private static final String PREFIX = "opik:connect:";

    private OpikConnectSessionKey() {
    }

    static String key(UUID sessionId) {
        return PREFIX + sessionId;
    }
}
