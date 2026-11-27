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

    /** The same tick, ending {@code reserve} before its deadline: a phase that leaves that time to the next one. */
    TickBudget before(@NonNull Duration reserve) {
        return new TickBudget(start, deadline.minus(reserve), clock);
    }

    /** The whole budget, from start to deadline. */
    Duration total() {
        return Duration.between(start, deadline);
    }

    Duration left() {
        return Duration.between(clock.instant(), deadline);
    }

    /**
     * Whether a window read can start and still leave {@code reserve} for an embed call after it, so the tick never
     * reads what it has no time to embed.
     */
    boolean hasTimeForRead(@NonNull Duration reserve) {
        return hasTimeFor(MIN_READ_TIME.plus(reserve));
    }

    boolean hasTimeFor(@NonNull Duration call) {
        return left().compareTo(call) >= 0;
    }

    /** A read's {@code max_execution_time}: {@code max}, cut to the whole seconds left before {@code reserve}, at least 1. */
    long readSeconds(@NonNull Duration max, @NonNull Duration reserve) {
        return Math.max(1, Math.min(max.toSeconds(), left().minus(reserve).toSeconds()));
    }
}
