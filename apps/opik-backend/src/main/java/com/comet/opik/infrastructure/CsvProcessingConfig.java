package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Configuration for CSV processing thread pool.
 * Controls dedicated thread pool for asynchronous CSV file processing.
 */
@Data
public class CsvProcessingConfig {

    /**
     * Number of threads in the CSV processing pool.
     * Default: 4 threads (suitable for I/O-bound CSV processing with DB writes)
     */
    @Valid @JsonProperty
    @NotNull @Min(1) private Integer threadPoolSize = 4;

    /**
     * Maximum number of CSV processing tasks that can be queued.
     * Default: 100 tasks (prevents memory exhaustion)
     * When queue is full, new tasks will be rejected with an error.
     */
    @Valid @JsonProperty
    @NotNull @Min(1) private Integer queueCapacity = 100;

    /**
     * Prefix for thread names in the CSV processing pool.
     * Default: "csv-processor-"
     * Threads will be named: csv-processor-1, csv-processor-2, etc.
     */
    @Valid @JsonProperty
    @NotNull private String threadNamePrefix = "csv-processor-";
}
