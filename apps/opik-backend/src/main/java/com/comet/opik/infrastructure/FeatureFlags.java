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
     * Checks if automatic annotation queue population is enabled: the score listener, the buffer flush job
     * and the routing consumer all stay dormant while it is off, and the UI hides the automation controls.
     *
     * @return true if annotation queue automation is enabled, false otherwise
     */
    public boolean isAnnotationQueueAutomationEnabled() {
        return config.getServiceToggles() != null
                && config.getServiceToggles().isAnnotationQueueAutomationEnabled();
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
