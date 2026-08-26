package com.comet.opik.api;

import com.comet.opik.api.validation.ProviderAuthCheckValidation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Builder;

import java.util.UUID;

/**
 * Test-connection request for a provider's dynamic token auth. Two modes, both executed by the
 * backend: by {@code provider_id} alone the stored recipe is used (secrets never transit the
 * browser); with an {@code auth_config} the submitted values are used, resolving
 * {@code __SECRET__} sentinels against the stored recipe when {@code provider_id} is also given.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@ProviderAuthCheckValidation
public record ProviderAuthCheck(
        @Schema(description = "Test the stored auth config of this provider; also the sentinel-resolution target when auth_config is sent") UUID providerId,
        @Valid @Schema(description = "Auth config to test as-submitted; omit to test the stored one") ProviderAuthConfig authConfig) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(
            @Schema(description = "Lifetime of the fetched token in seconds; the token itself is never returned") long lifetimeSeconds) {
    }
}
