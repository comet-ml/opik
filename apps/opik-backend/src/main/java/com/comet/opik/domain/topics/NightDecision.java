package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.HasValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** What the night did with a project: the {@code decision} attribute of {@code topics.nightly.decisions}. */
@Getter
@RequiredArgsConstructor
enum NightDecision implements HasValue {
    /** Queued, with its window's count. */
    NIGHTLY_UPDATE("nightly_update"),
    SKIP("skip"),
    /** The budget ended before the project. */
    NOT_REACHED("not_reached"),
    /** The project's window count read failed; it runs the next night. */
    COUNT_FAILED("count_failed");

    private final String value;
}
