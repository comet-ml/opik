package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class TraceQueryConfig {

    /**
     * When false, the last-updated-trace-at lookup query appends {@code use_skip_indexes = 0}, forcing ClickHouse to
     * skip the {@code idx_traces_last_updated_at} minmax index and scan the project partition in full. This is an
     * operational escape hatch for when the skip index misbehaves; leave enabled in normal operation.
     */
    @JsonProperty
    private boolean lastUpdatedTraceAtSkipIndexesEnabled = true;
}
