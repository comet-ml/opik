package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.net.DestinationGuard;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

/**
 * Tuning for dynamic token auth on custom LLM providers (the {@code auth_config} recipes).
 */
@Data
public class LlmProviderTokenAuthConfig {

    /**
     * A cached token is refreshed once its remaining lifetime drops below this fraction of the
     * total lifetime. Proportional rather than absolute so the same default behaves correctly for
     * a 60-second token and a 25-hour one.
     */
    @JsonProperty
    @DecimalMin("0.0") @DecimalMax("0.99") private double refreshFraction = 0.25;

    @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    private Duration fetchTimeout = Duration
            .seconds(10);

    /**
     * Lease time for the cross-pod single-flight lock around a token fetch. Must comfortably cover
     * one fetch, so a pod dying mid-fetch doesn't block the others for long.
     */
    @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    private Duration lockTimeout = Duration.seconds(15);

    @JsonProperty
    @Min(1) @Max(10_000_000) private int maxResponseChars = 1_000_000;

    /**
     * SSRF guard on the token URL: {@code strict} refuses non-HTTPS and private/internal
     * destinations; {@code relaxed} allows internal gateways. Strict by default so a missing
     * override fails loudly instead of exposing the deployment; the self-hosted distributions
     * can ship {@code relaxed} explicitly.
     */
    @JsonProperty
    @NotNull private DestinationGuard.Mode destinationGuard = DestinationGuard.Mode.STRICT;
}
