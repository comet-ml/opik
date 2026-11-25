package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.HasValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Why a tick found a trace without a vector and did not write one. A later tick finds such a trace again, except one
 * with a {@code topic_vector_status} row: an input refused on its own, or a zero or non-finite vector.
 */
@Getter
@RequiredArgsConstructor
public enum SkipReason implements HasValue {
    /** The service backed off: busy, failing, out of time, or refusing the workspace. */
    EMBED_FAILED("embed_failed"),
    /**
     * The service refused the inputs. An input refused on its own gets a status row; the inputs of a refused chunk not
     * narrowed down within {@link TopicEmbedder#MAX_SPLIT_CALLS} are sent again by a later tick.
     */
    EMBED_REFUSED("embed_refused"),
    /** A zero or non-finite vector (status row), or a vector of the wrong dimensions (sent again by a later tick). */
    INVALID_VECTOR("invalid_vector"),
    OVER_BUDGET("over_budget"),
    INSERT_FAILED("insert_failed");

    private final String value;
}
