package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.HasValue;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of {@code topic_nights}: one night of the nightly job, the UTC date it runs for. {@code projects} is a JSON
 * object of the night's projects per decision, set when the night ends.
 */
@Builder(toBuilder = true)
public record TopicNight(
        @NonNull LocalDate night,
        @NonNull Instant startedAt,
        @Nullable Instant finishedAt,
        @NonNull Outcome outcome,
        int eligibleWorkspaces,
        int countFailures,
        @Nullable String projects) {

    @Getter
    @RequiredArgsConstructor
    public enum Outcome implements HasValue {
        RUNNING("running"),
        FINISHED("finished"),
        FAILED("failed");

        private final String value;
    }
}
