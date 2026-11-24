package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.HasValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Why a tick found a trace without a vector and did not write one. A later tick finds every such trace again. */
@Getter
@RequiredArgsConstructor
public enum SkipReason implements HasValue {
    /** The service backed off: busy, failing, out of time, or refusing the workspace. */
    EMBED_FAILED("embed_failed"),
    /** The service refused the inputs: a later call gets the same answer, so they are settled. */
    EMBED_REFUSED("embed_refused"),
    INVALID_VECTOR("invalid_vector"),
    OVER_BUDGET("over_budget"),
    INSERT_FAILED("insert_failed");

    private final String value;
}
