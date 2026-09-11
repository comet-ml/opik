package com.comet.opik.domain.mcpoauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.time.Instant;

/**
 * What the introspection endpoint ({@code POST /opik/auth-oauth}) tells a resource server about a live access
 * token. {@code expiresAt} is the token row's expiry: opik-mcp caches a "valid" answer until then instead of
 * re-asking on a fixed timer, which closes the window in which an expired token is still forwarded (OPIK-8252).
 * Additive — resource servers that predate the field ignore it.
 */
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ValidatedToken(
        String userName,
        String workspaceId,
        String workspaceName,
        String resource,
        Instant expiresAt) {
}
