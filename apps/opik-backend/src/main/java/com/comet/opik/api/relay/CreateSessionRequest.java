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

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateSessionRequest(
        @NotNull UUID projectId,
        // @Size(44) is a weak proxy for "32 bytes decoded" — 31-byte inputs also
        // base64-encode to 44 characters. The authoritative byte-length check
        // lives in RelayServiceImpl.create.
        @NotBlank @Size(min = 44, max = 44) String activationKey,
        // null means "use the service default (300)".
        @Min(60) @Max(600) Integer ttlSeconds) {
}
