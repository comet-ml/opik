package com.comet.opik.domain.mcpoauth;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;

/**
 * One MCP client a user has connected in a workspace. Unlike {@link McpOAuthToken} this row outlives the
 * grant — it is never scrubbed — so it can answer "has this user ever connected this client", and carries the
 * client's display metadata for a future connected-clients UI.
 */
@Builder(toBuilder = true)
public record McpClientConnection(
        @NonNull String id,
        @NonNull String userName,
        @NonNull String workspaceName,
        @NonNull String workspaceId,
        @NonNull String clientId,
        @NonNull String clientName,
        String logoUri,
        @NonNull String resource,
        @NonNull String redirectUri,
        Instant firstConnectedAt,
        Instant lastConnectedAt,
        /**
         * Whether a live grant still backs this connection. Populated by reads only — like the timestamps
         * above it is derived by the database, and is ignored when the row is written.
         */
        boolean active) {
}
