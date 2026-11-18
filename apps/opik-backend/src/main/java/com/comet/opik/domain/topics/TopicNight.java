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
 * One row of {@code topic_nights}: one night of the nightly job, the UTC date it runs for. What the night decided per
 * project is in {@code topic_night_projects}.
 */
@Builder(toBuilder = true)
public record TopicNight(
        @NonNull LocalDate night,
        @NonNull Instant startedAt,
        @Nullable Instant finishedAt,
        @NonNull Outcome outcome) {

    @Getter
    @RequiredArgsConstructor
    public enum Outcome implements HasValue {
        RUNNING("running"),
        FINISHED("finished"),
        FAILED("failed");

        private final String value;
    }
}
