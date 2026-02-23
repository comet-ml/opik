package com.comet.opik.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OptimizerConfigEnv(
        UUID id,
        UUID projectId,
        @NotBlank @Size(max = 50, message = "envName cannot exceed 50 characters") String envName,
        @NotNull UUID blueprintId,
        String createdBy,
        Instant createdAt,
        String lastUpdatedBy,
        Instant lastUpdatedAt) {
}
