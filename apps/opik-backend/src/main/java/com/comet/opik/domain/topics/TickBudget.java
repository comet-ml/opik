package com.comet.opik.domain.topics;

import lombok.NonNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** One tick's time: what is left before its deadline, as {@code clock} tells it. */
record TickBudget(@NonNull Instant start, @NonNull Instant deadline, @NonNull Clock clock) {

    /**
     * ClickHouse's {@code max_execution_time} is in whole seconds and 0 means no limit, so a read started with less than
     * a second left would run unbounded: a read starts only with at least this much left.
     */
    static final Duration MIN_READ_TIME = Duration.ofSeconds(1);

    static TickBudget of(@NonNull Instant start, @NonNull Duration budget, @NonNull Clock clock) {
        return new TickBudget(start, start.plus(budget), clock);
    }

    Duration left() {
        return Duration.between(clock.instant(), deadline);
    }

    boolean hasTimeForRead() {
        return hasTimeFor(MIN_READ_TIME);
    }

    boolean hasTimeFor(@NonNull Duration call) {
        return left().compareTo(call) >= 0;
    }

    /** A read's {@code max_execution_time}: {@code max}, cut to the whole seconds left, at least 1. */
    long readSeconds(@NonNull Duration max) {
        return Math.max(1, Math.min(max.toSeconds(), left().toSeconds()));
    }
}
