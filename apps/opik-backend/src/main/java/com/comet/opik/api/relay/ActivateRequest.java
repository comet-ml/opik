package com.comet.opik.api.relay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * Request body for {@code POST /v1/private/relay/sessions/{sessionId}/activate}.
 *
 * @param runnerName human-readable runner name the client committed to via HMAC.
 * @param hmac       base64-encoded HMAC-SHA256 tag over
 *                   {@code sessionIdBytes || SHA256(runnerNameBytes)}.
 *                   A valid HMAC-SHA256 tag is 32 raw bytes, which is 44 characters
 *                   with standard base64 padding or 43 without.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ActivateRequest(
        @NotBlank @Size(max = 128) String runnerName,
        @NotBlank @Size(min = 43, max = 44) String hmac) {
}
