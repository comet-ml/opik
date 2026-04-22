package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

@Data
public class AnalyticsConfig {

    @Valid @JsonProperty
    private boolean enabled;

    @Valid @JsonProperty
    private String environment;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.DAYS)
    @MaxDuration(value = 90, unit = TimeUnit.DAYS)
    private Duration firstTraceCreatedDedupTtl;
}
