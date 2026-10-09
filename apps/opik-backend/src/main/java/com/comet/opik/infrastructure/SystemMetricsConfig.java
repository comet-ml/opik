package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

@Data
public class SystemMetricsConfig {

    @JsonProperty
    private boolean enabled;

    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    @MaxDuration(value = 7, unit = TimeUnit.DAYS)
    private Duration maxQueryRange = Duration.hours(24);

    @Min(1) @Max(5_000) @JsonProperty
    private int maxBatchSize = 1_000;

    @Min(100) @Max(100_000) @JsonProperty
    private int maxQueryPoints = 10_000;
}
