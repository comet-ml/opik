package com.comet.opik.domain.pairing;

import java.util.UUID;

final class PairingSessionKey {

    private static final String PREFIX = "opik:pairing:";

    private PairingSessionKey() {
    }

    static String key(UUID sessionId) {
        return PREFIX + sessionId;
    }
}
