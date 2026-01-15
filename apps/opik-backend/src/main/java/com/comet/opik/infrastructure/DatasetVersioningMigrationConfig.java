package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DatasetVersioningMigrationConfig {

    @JsonProperty
    @NotNull private boolean enabled;

    @JsonProperty
    @NotNull @Min(1) private int batchSize;

    @JsonProperty
    @NotNull @Min(1) private int lockTimeoutSeconds;
}
