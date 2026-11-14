package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A project's topics over the window: the last {@code topics.build.windowDays} UTC days (default 30), today included.
 * Fields that exist only in some states are omitted in the others.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectTopics(
        @Schema(description = "disabled: the workspace is not opted in to topics. collecting: the project has no "
                + "topics yet. ready: topics with counts") Status status,
        @Schema(description = "The UTC date of the request, the window's last day") LocalDate today,
        @Schema(description = "The window's first UTC day, inclusive") LocalDate windowFrom,
        @Nullable @Schema(description = "When the project's last saved run (a first build or a nightly update) "
                + "finished; omitted before the first one") Instant lastRunAt,
        @Nullable @Schema(description = "collecting only: the project's newest run when it is running or failed; "
                + "omitted when it saved or there is none. The runbook's 'Reading topics' section tells the "
                + "collecting cases apart") Attempt attempt,
        @Nullable @Schema(description = "collecting only: traces with a vector in the window") Long vectorsInWindow,
        @Nullable @Schema(description = "collecting only: traces with a vector the first build needs") Long minVectors,
        @Nullable @Schema(description = "ready only: traces with a topic in the window, Other "
                + "included") Long totalTraces,
        @Nullable @Schema(description = "ready only: the first day in the window with a labelled trace; earlier "
                + "days are 0 because nothing was labelled. Omitted when the window has none") LocalDate labelledFrom,
        @Schema(description = "The live topics in rank order; empty unless ready") List<TopicStats> topics,
        @Nullable @Schema(description = "ready only: the traces per day with no topic, or with a topic that is no "
                + "longer live. Add it to the topics' series for the project's total per day") OtherStats other) {

    @Getter
    @RequiredArgsConstructor
    public enum Status {
        DISABLED("disabled"),
        COLLECTING("collecting"),
        READY("ready");

        @JsonValue
        private final String value;
    }

    /** A collecting project's newest run, when it did not save. */
    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Attempt(
            @Schema(description = "running: the run started and has not ended. failed: it ended without "
                    + "saving") Status status) {

        @Getter
        @RequiredArgsConstructor
        public enum Status {
            RUNNING("running"),
            FAILED("failed");

            @JsonValue
            private final String value;
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TopicStats(
            @Schema(description = "The topic id, the value the traces filter uses") UUID id,
            @Schema(description = "The topic's name") String name,
            @Schema(description = "What the topic's traces are about") String definition,
            @Schema(description = "The UTC date the topic was made: for a first build's topics, the day the run "
                    + "started") LocalDate born,
            @Schema(description = "Born after the project's first build, in the last 7 days") boolean isNew,
            @Schema(description = "Traces of the topic in the window") long count,
            @Schema(description = "count / total_traces, from 0 to 1, not rounded") double share,
            @Schema(description = "1-based: topics by count, most first, then the earlier born, then the id") int rank,
            @Schema(description = "One entry per day of the window, oldest first, today last") List<DayCount> series) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OtherStats(
            @Schema(description = "One entry per day of the window, oldest first, today last") List<DayCount> series) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DayCount(
            @Schema(description = "A UTC date") LocalDate day,
            @Schema(description = "Traces created on the day") long count) {
    }
}
