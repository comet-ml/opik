package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.HasValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The part of an arrival step that failed: the {@code phase} of {@code topics.arrival.failures}. */
@Getter
@RequiredArgsConstructor
enum ArrivalPhase implements HasValue {
    /** The projects' saved lists read in MySQL. */
    READ("read"),
    /** Any other failure of the step. */
    ASSIGN("assign"),
    /** The {@code trace_topics} insert. */
    INSERT("insert");

    private final String value;
}
