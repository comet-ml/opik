package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A project's topics over the last 30 UTC days. Fields that exist only in some states are omitted in the others.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectTopics(
        @Schema(description = "disabled: the workspace is not opted in to topics. collecting: the project has no "
                + "topics yet. ready: topics with counts") Status status,
        @Schema(description = "The UTC date of the request") LocalDate today,
        @Schema(description = "The window's first UTC day, inclusive: today - 29 days") LocalDate windowFrom,
        @Schema(description = "The window's last UTC day, inclusive: today") LocalDate windowUntil,
        @Schema(description = "When the last nightly run that saved finished; omitted before the first one") Instant lastRunAt,
        @Schema(description = "collecting only: traces with a vector in the window") Long vectorsInWindow,
        @Schema(description = "collecting only: traces with a vector the first build needs") Long minVectors,
        @Schema(description = "ready only: traces with a topic in the window, Other included") Long totalTraces,
        @Schema(description = "ready only: the first day in the window with a labelled trace; earlier days are 0 "
                + "because nothing was labelled. Omitted when the window has none") LocalDate labelledFrom,
        @Schema(description = "The live topics in rank order; empty unless ready") List<TopicStats> topics,
        @Schema(description = "ready only: traces with no topic") OtherStats other) {

    @Getter
    @RequiredArgsConstructor
    public enum Status {
        DISABLED("disabled"),
        COLLECTING("collecting"),
        READY("ready");

        @JsonValue
        private final String value;
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TopicStats(
            @Schema(description = "The topic id, the value the traces filter uses") UUID id,
            String name,
            String definition,
            @Schema(description = "The UTC date of the night that made the topic") LocalDate born,
            @Schema(description = "Born after the project's first build, in the last 7 days") boolean isNew,
            @Schema(description = "The day the topic became inactive; omitted when active") LocalDate inactiveSince,
            @Schema(description = "Traces of the topic in the window") long count,
            @Schema(description = "count / total_traces, from 0 to 1, not rounded") double share,
            @Schema(description = "Traces today minus traces yesterday; today is not over") long change,
            @Schema(description = "1-based: active topics by count, then inactive ones by count") int rank,
            @Schema(description = "30 days, oldest first, today last") List<DayCount> series) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OtherStats(
            @Schema(description = "The all-zeros id, the value the traces filter uses for Other") UUID id,
            @Schema(description = "Traces with no topic in the window, stale included") long count,
            @Schema(description = "Of count: traces labelled with a topic that is no longer live") long stale,
            @Schema(description = "count / total_traces, from 0 to 1, not rounded") double share,
            @Schema(description = "Traces today minus traces yesterday; today is not over") long change,
            @Schema(description = "30 days, oldest first, today last") List<DayCount> series) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DayCount(
            @Schema(description = "A UTC date") LocalDate day,
            long count) {
    }
}
