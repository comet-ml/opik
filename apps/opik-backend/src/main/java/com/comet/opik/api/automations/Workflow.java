package com.comet.opik.api.automations;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A recipe of automations that does one job on a cadence, read for one project: whether it is on, what
 * it would create, and what the last 14 days say it would have done.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Workflow(
        String recipe,
        int version,
        String name,
        String description,
        State state,
        boolean prerequisitesMet,
        @Nullable String blockedReason,
        List<Automation> automations,
        AutomationDryRun.Estimate estimate,
        Overrides overrides,
        List<String> availableScoreNames) {

    /** What a caller may change when enabling a recipe; the defaults the recipe would otherwise use. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Overrides(
            @Nullable String scoreName,
            @Nullable Double threshold,
            @Nullable String testSuiteName,
            @Nullable String annotationQueueName,
            @Nullable Integer perDay,
            @Nullable Integer maxItemsPerDay) {
    }

    @Getter
    @RequiredArgsConstructor
    public enum State {
        OFF("off"),
        PARTLY_ON("partly_on"),
        ON("on");

        @JsonValue
        private final String value;

        @JsonCreator
        public static State fromString(String value) {
            return Arrays.stream(values())
                    .filter(state -> state.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown workflow state '%s'".formatted(value)));
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EnableRequest(
            @NotBlank String recipe,
            @NotNull UUID projectId,
            @Nullable Overrides overrides,
            @Nullable Automation.CreatedVia createdVia) {
    }

    /** What enabling did, or with a dry run would do. {@code setup} is reserved for recipes that need the customer's CI. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Enabled(
            List<Automation> created,
            List<String> wouldCreate,
            AutomationDryRun.Estimate estimate,
            @Nullable Setup setup,
            List<String> warnings) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Setup(@Nullable String githubAction) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DisableRequest(@NotBlank String recipe, @NotNull UUID projectId) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Disabled(List<UUID> disabled) {
    }
}
