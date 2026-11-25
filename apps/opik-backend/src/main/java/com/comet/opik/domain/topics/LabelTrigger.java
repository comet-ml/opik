package com.comet.opik.domain.topics;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Who called a labelling pass: the job right after a run's save, or the job going on with an unfinished pass. */
@Getter
@RequiredArgsConstructor
enum LabelTrigger {
    RUN("run"),
    RESUME("resume");

    private final String value;
}
