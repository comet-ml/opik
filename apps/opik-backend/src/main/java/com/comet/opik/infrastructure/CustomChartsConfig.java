package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;

import java.util.concurrent.TimeUnit;

@Data
public class CustomChartsConfig {

    /**
     * Whether Custom Charts is on for every workspace. Off (the default) disables the feature everywhere.
     *
     * <p>No getter, so it cannot be read without Ollie's toggle; read it via {@link #isEnabled(ServiceTogglesConfig)}.
     */
    @JsonProperty
    @Getter(AccessLevel.NONE)
    private boolean enabled;

    /**
     * Whether Custom Charts may be used: Ollie is on and the feature is enabled. The one check behind the chart
     * endpoints, the UI toggle and the health check, so they cannot drift apart.
     */
    public boolean isEnabled(@NonNull ServiceTogglesConfig serviceToggles) {
        return serviceToggles.isOllieEnabled() && enabled;
    }

    /**
     * Whether the public Custom Charts endpoints are exposed on top of the feature itself. Off (the default) keeps
     * them answering 501 while the internal and widget endpoints work as before.
     *
     * <p>No getter; read it via {@link #isPublicEndpointsEnabled(ServiceTogglesConfig)}.
     */
    @JsonProperty
    @Getter(AccessLevel.NONE)
    private boolean publicEndpointsEnabled;

    /**
     * Whether the public endpoints may be used: Custom Charts is enabled and the public endpoints are switched on.
     */
    public boolean isPublicEndpointsEnabled(@NonNull ServiceTogglesConfig serviceToggles) {
        return isEnabled(serviceToggles) && publicEndpointsEnabled;
    }

    /**
     * Beyond this many distinct ids in one result, name enrichment resolves none and no row gets a name column,
     * rather than labelling some rows and not others.
     */
    @JsonProperty
    private @Min(1) @Max(50_000) int maxNameLookupIds = 5_000;

    /**
     * How many free-form SQL queries one workspace may run at once across every backend pod. Keep it below the EXTENDED
     * account's per-replica {@code max_concurrent_queries_for_user}, so one workspace cannot take the whole cap.
     */
    @JsonProperty
    private @Min(1) @Max(50) int maxConcurrentQueriesPerWorkspace = 4;

    /**
     * The longest a query holds its slot. It only matters when a pod dies mid-query; keep it above the ClickHouse
     * profile's max_execution_time so a live query never loses its slot.
     */
    @JsonProperty
    private @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS) Duration concurrencySlotLease = Duration
            .seconds(200);
}
