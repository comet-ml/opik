package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Configuration for the dataset version items_total migration.
 * This migration calculates and updates the items_total field for dataset versions
 * that were created by the Liquibase migration (identified by dataset_id = dataset_version_id).
 */
@Data
public class DatasetVersionItemsTotalMigrationConfig {

    /**
     * Enable/disable the migration.
     * Set to false after migration is complete.
     */
    @JsonProperty
    @NotNull private boolean enabled;

    /**
     * Number of dataset versions to process in each batch.
     * Smaller batches reduce memory usage but increase total migration time.
     */
    @JsonProperty
    @NotNull @Min(1) @Max(1000) private int batchSize;

    /**
     * Lock timeout in seconds to prevent concurrent migrations.
     * Should be long enough to complete the migration.
     */
    @JsonProperty
    @NotNull @Min(1) private int lockTimeoutSeconds;
}
