package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.HasValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** What topic on arrival did with a trace: the {@code outcome} of {@code topics.arrival.labelled}. */
@Getter
@RequiredArgsConstructor
enum ArrivalOutcome implements HasValue {
    /** A row with one of the project's topics was written. */
    TOPIC("topic"),
    /** A row with Other was written. */
    OTHER("other"),
    /** The project has no saved list, or one with no topic: no row. */
    NO_PROTOTYPES("no_prototypes"),
    /** No time was left of {@code topics.arrival.timeout} or of the tick: no row. */
    OVER_BUDGET("over_budget"),
    /** Arrival stopped earlier in the tick: no row. */
    AFTER_FAILURE("after_failure");

    private final String value;
}
