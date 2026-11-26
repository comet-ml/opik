package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.HasValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** How a night went: the {@code outcome} attribute of {@code topics.nightly.nights}. */
@Getter
@RequiredArgsConstructor
enum NightOutcome implements HasValue {
    FINISHED("finished"),
    /** A read of the night's projects failed; the next plan tries again. */
    FAILED("failed");

    private final String value;
}
