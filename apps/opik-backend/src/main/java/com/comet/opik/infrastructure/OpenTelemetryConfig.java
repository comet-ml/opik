package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import lombok.Data;

import java.util.concurrent.TimeUnit;

@Data
public class OpenTelemetryConfig {
    @Valid @JsonProperty
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 7, unit = TimeUnit.DAYS)
    private Duration ttl;
}
