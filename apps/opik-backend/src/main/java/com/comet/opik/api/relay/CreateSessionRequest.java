package com.comet.opik.api.relay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.UUID;

/**
 * Request body for {@code POST /v1/private/relay/sessions}.
 *
 * @param projectId     target project; must exist in the caller's workspace.
 * @param activationKey base64-encoded activation key. Length bound of 44 characters
 *                      is a weak proxy — the authoritative check is that the decoded
 *                      byte array is exactly 32 bytes, enforced by the service layer.
 * @param ttlSeconds    session TTL in seconds; bounded {@code [60, 600]}.
 *                      {@code null} means "use the service default (300)".
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateSessionRequest(
        @NotNull UUID projectId,
        @NotBlank @Size(min = 44, max = 44) String activationKey,
        @Min(60) @Max(600) Integer ttlSeconds) {
}
