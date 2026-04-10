package com.comet.opik.api.connect;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ActivateRequest(
        @NotBlank @Size(max = 128) String runnerName,
        // Base64-encoded HMAC-SHA256 tag: 32 raw bytes → 43 chars unpadded / 44 chars padded.
        @NotBlank @Size(min = 43, max = 44) String hmac) {
}
