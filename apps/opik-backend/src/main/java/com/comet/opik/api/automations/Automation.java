package com.comet.opik.api.automations;

import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.Page;
import com.comet.opik.api.annotationqueue.Conditions;
import com.comet.opik.api.annotationqueue.ScoreConditionOperator;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * When an event happens on an Opik object, run numbered steps. One automation is one row in
 * {@code automation_rules} with the {@code automation} action, plus its triggers, steps and limits.
 *
 * <p>Events and step types are plain strings on the wire rather than enums: an unknown value has to come
 * back as a {@code validation_failed} with a code an agent can act on, which Jackson's enum failure cannot
 * say. {@link Event} and {@link StepType} are the closed sets the service validates against.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Automation(
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID id,
        @NotNull UUID projectId,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String projectName,
        @NotBlank @Size(max = 150) String name,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String action,
        @Nullable Boolean enabled,
        @Nullable @Valid Recipe recipe,
        @NotEmpty @Size(max = MAX_TRIGGERS) @Valid List<@NotNull Trigger> triggers,
        @NotEmpty @Size(max = MAX_STEPS) @Valid List<@NotNull Step> steps,
        @Nullable @Valid Limits limits,
        @Nullable CreatedVia createdVia,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable Stats stats,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) @Nullable PausedReason pausedReason,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy) {

    public static final int MAX_TRIGGERS = 5;
    public static final int MAX_STEPS = 10;
    public static final String ACTION = "automation";

    /** Which workflow recipe created the automation. Null for one a user wrote by hand. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Recipe(@NotBlank String id, @NotNull Integer version) {
    }

    /**
     * One way the automation fires. Triggers are joined by <em>or</em>. {@code conditions} is the score
     * language annotation queue routing already uses; {@code perDay} caps matches at N a day, picked at
     * random, for a sample that has to stay random.
     */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Trigger(
            @NotBlank String event,
            @Nullable Scope scope,
            @Nullable @Valid Conditions conditions,
            @Nullable @Positive Integer perDay) {
    }

    /**
     * One step. Which target field applies depends on {@code type}; names resolve to ids when the
     * automation is written and both come back on reads. {@code onlyIf} names an earlier step's output.
     */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Step(
            @NotBlank @Size(max = 100) String id,
            @NotBlank String type,
            @Nullable UUID annotationQueueId,
            @Nullable String annotationQueueName,
            @Nullable UUID testSuiteId,
            @Nullable String testSuiteName,
            @Nullable UUID datasetId,
            @Nullable String datasetName,
            @Nullable @Valid EvaluatorItem assertion,
            @Nullable @Valid StepConditions onlyIf) {
    }

    /** Daily ceilings. Hitting one pauses the automation until midnight UTC. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Limits(
            @Nullable @Positive Integer maxItemsPerDay,
            @Nullable @Positive Integer maxRunsPerDay,
            @Nullable @Positive BigDecimal maxCostUsdPerDay) {
    }

    /** The last seven days, from the run rollups. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Stats(int windowDays, long fired, long matched, long sampled, long stepsDone, long stepsSkipped,
            long stepsFailed, long itemsWritten, BigDecimal costUsd) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PausedReason(String limit, Instant until) {
    }

    /**
     * The same disjunction-of-conjunctions shape as {@link Conditions}, over an earlier step's outputs
     * ({@code step_output} is {@code "<step id>.<output>"}) instead of score names.
     */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StepConditions(@NotEmpty @Valid List<@NotNull Group> groups) {

        @Builder(toBuilder = true)
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
        public record Group(@NotEmpty @Valid List<@NotNull Condition> conditions) {
        }

        @Builder(toBuilder = true)
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
        public record Condition(@NotBlank String stepOutput, @NotNull ScoreConditionOperator operator,
                @NotNull Double value) {
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum Scope {
        TRACE("trace"),
        THREAD("thread");

        @JsonValue
        private final String value;

        @JsonCreator
        public static Scope fromString(String value) {
            return Arrays.stream(values())
                    .filter(scope -> scope.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown scope '%s'".formatted(value)));
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum CreatedVia {
        MCP("mcp"),
        UI("ui"),
        API("api");

        @JsonValue
        private final String value;

        @JsonCreator
        public static CreatedVia fromString(String value) {
            return Arrays.stream(values())
                    .filter(via -> via.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown created_via '%s'".formatted(value)));
        }
    }

    /** The events an automation can listen to. Only the ones with a producer today are listed. */
    @Getter
    @RequiredArgsConstructor
    public enum Event {
        FEEDBACK_SCORE_CREATED("feedback_score:created");

        private final String value;

        public static @Nullable Event fromString(String value) {
            return Arrays.stream(values())
                    .filter(event -> event.value.equals(value))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** The steps an automation can run. Only the ones with a handler today are listed. */
    @Getter
    @RequiredArgsConstructor
    public enum StepType {
        ANNOTATION_QUEUE_ADD("annotation_queue.add"),
        TEST_SUITE_ADD("test_suite.add"),
        DATASET_ADD("dataset.add");

        private final String value;

        public static @Nullable StepType fromString(String value) {
            return Arrays.stream(values())
                    .filter(type -> type.value.equals(value))
                    .findFirst()
                    .orElse(null);
        }
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AutomationPage(List<Automation> content, int page, int size, long total)
            implements
                Page<Automation> {
    }
}
