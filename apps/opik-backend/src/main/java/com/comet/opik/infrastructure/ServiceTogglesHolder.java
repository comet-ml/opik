package com.comet.opik.infrastructure;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

/**
 * Utility class to hold and provide access to ServiceTogglesConfig for components that cannot use dependency injection.
 * This is particularly useful for validators and other static contexts.
 *
 * This follows the same pattern as EncryptionUtils for accessing configuration in validators.
 */
@UtilityClass
public class ServiceTogglesHolder {

    private static ServiceTogglesConfig serviceToggles;

    public static void setConfig(@NonNull OpikConfiguration config) {
        serviceToggles = config.getServiceToggles();
    }

    public static ServiceTogglesConfig getServiceToggles() {
        if (serviceToggles == null) {
            throw new IllegalStateException("ServiceToggles not initialized");
        }
        return serviceToggles;
    }
}
