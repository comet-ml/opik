package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

/** Per-organization features the platform switches on, asked for on demand by the endpoints that need them. */
@Data
public class WorkspaceFeaturesConfig {

    /**
     * How long one workspace's answer for a feature is cached. A feature switched on or off in the platform is seen
     * within it. Zero asks the platform on every call.
     */
    @NotNull @JsonProperty
    @MinDuration(value = 0, unit = TimeUnit.SECONDS)
    private Duration cacheTtl = Duration.seconds(5);
}
