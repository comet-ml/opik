package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class BatchOperationsConfig {

    @Data
    public static class DatasetsConfig {
        private @Valid @JsonProperty @Positive int maxExperimentInClauseSize;
    }

    @Data
    public static class AnalyticsConfig {
        /**
         * Chunk size for bulk update operations (traces, spans, threads).
         * ClickHouse performs best with batch sizes of 100-1000 records per query.
         * Default: 100
         */
        private @Valid @JsonProperty @Positive int bulkUpdateChunkSize = 100;

        /**
         * Buffer size for streaming operations to batch records for efficient processing.
         * Default: 1000
         */
        private @Valid @JsonProperty @Positive int streamBufferSize = 1000;
    }

    @Valid @JsonProperty
    @NotNull private DatasetsConfig datasets;

    @Valid @JsonProperty
    @NotNull private AnalyticsConfig analytics = new AnalyticsConfig();

}
