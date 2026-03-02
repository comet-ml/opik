package com.comet.opik.domain.experiments.aggregations;

import java.util.UUID;

/**
 * Utility models for experiment aggregates operations.
 */
public class ExperimentAggregatesUtils {

    /**
     * Result of a batch processing operation.
     *
     * @param processedCount Number of items processed
     * @param lastCursor     The cursor position after processing
     */
    public record BatchResult(long processedCount, UUID lastCursor) {
    }

    /**
     * Helper for converting maps to parallel arrays for ClickHouse binding.
     * ClickHouse requires maps to be bound as separate key and value arrays.
     *
     * @param keys   Array of map keys
     * @param values Array of map values
     * @param <K>    Key type
     * @param <V>    Value type
     */
    public record MapArrays<K, V>(K[] keys, V[] values) {
    }
}
