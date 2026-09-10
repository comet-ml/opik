package com.comet.opik.domain.mcpoauth;

import com.comet.opik.api.validation.AbsoluteUri;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Set;

/**
 * RFC 7591 §2 client metadata; unknown fields are ignored.
 * Size caps mirror the mcp_oauth_clients column sizes.
 * <p>
 * {@code softwareId} is assigned by the client developer and is, per §2, the same across all installs and
 * versions of a product — so it names the MCP host itself, where {@code client_id} names one registration of
 * it.
 * <p>
 * The fields added for display carry no {@code @Size}: registration is the client's only way in, and these
 * were silently discarded before, so rejecting an over-long value would break a host that registers fine
 * today. {@link McpOAuthClientMapper} truncates and sanitises them instead.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ClientRegistrationRequest(
        @NotBlank @Size(max = 255) String clientName,
        @NotEmpty @Size(max = 10) Set<@NotBlank @Size(max = 2048) @AbsoluteUri String> redirectUris,
        @Size(max = 2048) String logoUri,
        String softwareId,
        String softwareVersion,
        String clientUri) {
}
