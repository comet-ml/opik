package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.net.DestinationGuard;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

@Data
public class MediaUrlCheckConfig {

    /**
     * Strict by default, and deliberately not set in the Helm chart's values: cloud deploys from that chart too, so a
     * relaxed value there would open this user-driven fetch to internal addresses unless an override caught it.
     */
    @JsonProperty
    @NotNull private DestinationGuard.Mode destinationGuard = DestinationGuard.Mode.STRICT;

    @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 30, unit = TimeUnit.SECONDS)
    private Duration connectTimeout = Duration.seconds(3);

    /**
     * Capped below the frontend's 8s request timeout: past it the frontend gives up and runs without the check, so
     * a slower verdict would never be shown.
     */
    @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 6, unit = TimeUnit.SECONDS)
    private Duration perUrlTimeout = Duration.seconds(5);
}
