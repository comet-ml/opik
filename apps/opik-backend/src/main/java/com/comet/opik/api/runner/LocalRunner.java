package com.comet.opik.api.runner;

import com.comet.opik.api.Page;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LocalRunner(
        UUID id,
        String name,
        UUID projectId,
        LocalRunnerStatus status,
        Instant connectedAt,
        List<Agent> agents) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Agent(
            String name,
            String description,
            String language,
            String executable,
            String sourceFile,
            @Valid List<Param> params,
            int timeout) {

        @Builder(toBuilder = true)
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
        public record Param(
                @NotBlank String name,
                @NotBlank String type) {
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LocalRunnerPage(
            int page,
            int size,
            long total,
            List<LocalRunner> content) implements Page<LocalRunner> {
    }
}
