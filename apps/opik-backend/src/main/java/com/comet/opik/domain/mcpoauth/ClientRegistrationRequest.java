package com.comet.opik.domain.mcpoauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.Set;

// RFC 7591 §2 client metadata; unknown fields are ignored.
// Size caps mirror the mcp_oauth_clients column sizes.
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ClientRegistrationRequest(
        @Size(max = 255) String clientName,
        @NotEmpty @Size(max = 10) Set<@NotEmpty @Size(max = 2048) String> redirectUris,
        @Size(max = 2048) String logoUri) {
}
