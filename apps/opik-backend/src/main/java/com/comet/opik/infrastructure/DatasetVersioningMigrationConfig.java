package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Configuration for dataset versioning migrations.
 * Includes both lazy migration (on-demand) and items_total migration (startup).
 */
@Data
public class DatasetVersioningMigrationConfig {

    /**
     * Enable/disable lazy migration (migrate datasets on first access).
     */
    @JsonProperty
    @NotNull private boolean lazyEnabled;

    /**
     * Enable/disable the items_total migration (runs on startup).
     * Set to false after migration is complete.
     */
    @JsonProperty
    @NotNull private boolean itemsTotalEnabled;

    /**
     * Number of dataset versions to process in each batch for items_total migration.
     * Smaller batches reduce memory usage but increase total migration time.
     */
    @JsonProperty
    @NotNull @Min(1) @Max(1000) private int itemsTotalDatasetsBatchSize;

    /**
     * Lock timeout in seconds to prevent concurrent items_total migrations.
     * Should be long enough to complete the migration.
     * Maximum: 7200 seconds (2 hours) to prevent indefinite resource locks.
     */
    @JsonProperty
    @NotNull @Min(1) @Max(7200) private int itemsTotalLockTimeoutSeconds;

    /**
     * Delay in seconds before starting the items_total migration job after application startup.
     * This allows the service to stabilize before running the migration.
     * Default: 30 seconds
     * Maximum: 3600 seconds (1 hour) to prevent excessive startup delays.
     */
    @JsonProperty
    @NotNull @Min(0) @Max(3600) private int itemsTotalStartupDelaySeconds = 30;

    /**
     * Job execution timeout in seconds for the items_total migration.
     * Should be long enough to complete the entire migration process.
     * Default: 3600 seconds (1 hour)
     * Maximum: 7200 seconds (2 hours) to prevent indefinite job execution.
     */
    @JsonProperty
    @NotNull @Min(1) @Max(7200) private int itemsTotalJobTimeoutSeconds = 3600;
}
