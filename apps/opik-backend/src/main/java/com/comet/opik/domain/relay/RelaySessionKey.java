package com.comet.opik.domain.relay;

import java.util.UUID;

/**
 * Redis key helper for relay pairing sessions. Matches the prefix convention
 * used by {@code LocalRunnerService} (e.g. {@code opik:runners:runner:<uuid>}).
 */
final class RelaySessionKey {

    private static final String PREFIX = "opik:relay:";

    private RelaySessionKey() {
    }

    static String key(UUID sessionId) {
        return PREFIX + sessionId;
    }
}
