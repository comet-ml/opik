package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Configuration for ClickHouse async insert functionality.
 * When enabled, individual insert operations will use async_insert settings
 * to reduce IO overhead for concurrent requests.
 */
@Data
@Accessors(fluent = true)
public class AsyncInsertConfig {

    /**
     * Enable async insert functionality for individual writes.
     * When true, individual inserts will include SETTINGS clause with:
     * - async_insert=1
     * - wait_for_async_insert=1
     * - async_insert_use_adaptive_busy_timeout=1
     *
     * This does NOT affect batch operations.
     */
    @NotNull @JsonProperty
    private boolean enabled;
}