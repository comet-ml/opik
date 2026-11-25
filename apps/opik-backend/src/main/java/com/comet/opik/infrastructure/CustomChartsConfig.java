package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;

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
}
