package com.comet.opik.api.connect;

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
        // 32 raw bytes → 43 chars unpadded / 44 chars padded base64. The size bound
        // is a cheap rejection of obvious garbage; the authoritative byte-length
        // check lives in OpikConnectServiceImpl.create.
        @NotBlank @Size(min = 43, max = 44) String activationKey,
        // null means "use the service default (300)".
        @Min(60) @Max(600) Integer ttlSeconds) {
}
