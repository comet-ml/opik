package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DatasetVersionRetrieveRequest(
        @NotBlank @Pattern(regexp = "^v\\d+$", message = "Version name must be in format 'vN' (e.g., 'v1', 'v373')") @Schema(description = "Version name in format 'vN' (e.g., 'v1', 'v373')", example = "v1") String versionName) {
}
