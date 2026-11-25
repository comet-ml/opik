package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.HasValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Why the night skipped a project: the {@code reason} attribute of a skip in {@code topics.nightly.decisions}. */
@Getter
@RequiredArgsConstructor
enum NightSkipReason implements HasValue {
    /** Under {@code topics.run.minVectors}. */
    SMALL("small"),
    DELETED("deleted"),
    PAUSED("paused"),
    /** Its saved list's window already ends at or after the night's start. */
    ALREADY_SAVED("already_saved"),
    /** No list. */
    NO_LIST("no_list");

    private final String value;
}
