package com.comet.opik.domain.topics;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * How a labelling pass ended. Complete: every trace of the window with a usable vector has a row of the run, and the
 * run's labels are marked complete. Cut: the deadline came, or the subscriber cancelled. Failed: a read, an insert or a
 * page check stopped it. A pass that is not complete is finished by a later one.
 */
@Getter
@RequiredArgsConstructor
enum LabelPassOutcome {
    COMPLETE("complete"),
    CUT("cut"),
    FAILED("failed");

    private final String value;
}
