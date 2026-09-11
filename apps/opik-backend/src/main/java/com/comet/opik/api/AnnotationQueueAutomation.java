package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
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

import java.util.Arrays;
import java.util.List;

/**
 * Rules for automatically populating an annotation queue from feedback scores. Rides on the annotation
 * queue payload rather than being its own resource — automation is a property of a queue.
 *
 * <p>Score conditions are source-agnostic on purpose: a score written by an online-evaluation rule, a
 * human annotator or the SDK is treated identically, because they all land in the same analytics tables.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AnnotationQueueAutomation(
        @JsonView({
                AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @NotNull Boolean enabled,

        // Nullable so flipping the toggle off is a one-field request: {"enabled": false} keeps the stored
        // conditions, which also makes enable/disable idempotent and stops two clients racing on the
        // toggle from clobbering each other's conditions. "An enabled automation needs at least one
        // group" is a cross-field rule and lives in the service.
        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @Nullable @Valid Conditions conditions,

        // Ceiling on how large automation is allowed to grow the queue: once the queue holds this many
        // items, automation stops adding. Absent means no ceiling. Nullable for the same reason as
        // conditions — a toggle-only request must not silently drop it.
        @JsonView({AnnotationQueue.View.Public.class,
                AnnotationQueue.View.Write.class}) @Nullable @Positive Integer maxItemsInQueue) {

    public static final int MAX_GROUPS = 5;
    public static final int MAX_CONDITIONS_PER_GROUP = 5;

    /**
     * Disjunction of conjunctions: an item matches when <em>any</em> group matches, and a group matches
     * when <em>all</em> of its conditions do. Mirrors the "Add AND condition" / "Add OR group" controls.
     */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Conditions(
            @JsonView({
                    AnnotationQueue.View.Public.class,
                    AnnotationQueue.View.Write.class}) @NotEmpty @Size(max = MAX_GROUPS, message = "cannot exceed "
                            + MAX_GROUPS + " groups") @Valid List<@NotNull ConditionGroup> groups) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ConditionGroup(
            @JsonView({
                    AnnotationQueue.View.Public.class,
                    AnnotationQueue.View.Write.class}) @NotEmpty @Size(max = MAX_CONDITIONS_PER_GROUP, message = "cannot exceed "
                            + MAX_CONDITIONS_PER_GROUP
                            + " conditions") @Valid List<@NotNull ScoreCondition> conditions) {
    }

    /**
     * A single threshold on a named feedback score. The name is deliberately not validated against the
     * workspace's feedback definitions — configuring an automation before the score exists is a legitimate
     * order of operations.
     */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScoreCondition(
            @JsonView({
                    AnnotationQueue.View.Public.class,
                    AnnotationQueue.View.Write.class}) @NotBlank @Size(max = 255) String scoreName,

            @JsonView({AnnotationQueue.View.Public.class,
                    AnnotationQueue.View.Write.class}) @NotNull Operator operator,

            @JsonView({AnnotationQueue.View.Public.class,
                    AnnotationQueue.View.Write.class}) @NotNull Double value) {
    }

    /**
     * {@code EQUAL} is for categorical scores — a boolean written as 0/1, or a rating coded as an integer.
     * Note what it compares: the <em>effective</em> score, which is averaged across authors, so an equality
     * that matches while one annotator has scored an item can stop matching once a second one disagrees
     * (1 and 0 average to 0.5). Exact matching is dependable where a single author writes the score, which
     * is the case for LLM judges and SDK-written scores.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Operator {

        GREATER_THAN(">"),
        LESS_THAN("<"),
        EQUAL("=");

        @JsonValue
        private final String value;

        @JsonCreator
        public static Operator fromString(String value) {
            return Arrays.stream(values())
                    .filter(operator -> operator.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown score condition operator '%s'".formatted(value)));
        }
    }
}
