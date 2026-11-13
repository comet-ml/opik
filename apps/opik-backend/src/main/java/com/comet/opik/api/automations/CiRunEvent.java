package com.comet.opik.api.automations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.UUID;

/** What a CI job tells Opik when it runs: enough to fire {@code ci:run} and to post a check back. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CiRunEvent(
        @NotNull UUID projectId,
        @NotBlank String repository,
        @Nullable String branch,
        @NotBlank String commit,
        @Nullable String runUrl) {
}
