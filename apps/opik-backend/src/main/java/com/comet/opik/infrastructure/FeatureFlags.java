package com.comet.opik.infrastructure;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ForbiddenException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for checking feature flags and toggles.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class FeatureFlags {

    private final @NonNull OpikConfiguration config;

    /**
     * Checks if dataset versioning feature is enabled.
     *
     * @return true if dataset versioning is enabled, false otherwise
     */
    public boolean isDatasetVersioningEnabled() {
        return config.getServiceToggles() != null
                && config.getServiceToggles().isDatasetVersioningEnabled();
    }

    /**
     * Checks if CSV upload feature is enabled.
     *
     * @return true if CSV upload is enabled, false otherwise
     */
    public boolean isCsvUploadEnabled() {
        return config.getServiceToggles() != null
                && config.getServiceToggles().isCsvUploadEnabled();
    }

    /**
     * Checks if dataset versioning feature is enabled and throws ForbiddenException if not.
     *
     * @throws ForbiddenException if dataset versioning is not enabled
     */
    public void checkDatasetVersioningEnabled() {
        if (!isDatasetVersioningEnabled()) {
            log.warn("Dataset versioning feature is disabled, returning 403");
            throw new ForbiddenException("Dataset versioning feature is not enabled");
        }
    }
}
