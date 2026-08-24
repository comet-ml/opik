package com.comet.opik.api.validation;

import com.comet.opik.api.ProviderAuthConfig;
import com.comet.opik.utils.ValidationUtils;
import lombok.NonNull;
import org.apache.commons.collections4.CollectionUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Requiredness rules for {@link ProviderAuthConfig}, shared by the create validator and the
 * update/test paths. Field-level Jakarta annotations can't carry these because the empty
 * clear-object ({@code {}}) must pass bean validation.
 */
public final class ProviderAuthConfigValidator {

    private ProviderAuthConfigValidator() {
    }

    public static List<String> validationErrors(@NonNull ProviderAuthConfig authConfig) {
        var errors = new ArrayList<String>();
        if (!ValidationUtils.isAbsoluteUri(authConfig.tokenUrl())) {
            errors.add("auth_config.token_url must be a valid absolute URI");
        } else {
            String scheme = URI.create(authConfig.tokenUrl()).getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                errors.add("auth_config.token_url must use http or https");
            }
        }
        if (CollectionUtils.isEmpty(authConfig.credentials())) {
            errors.add("auth_config.credentials must not be empty");
        } else {
            Set<String> seen = new HashSet<>();
            authConfig.credentials().stream()
                    .map(ProviderAuthConfig.Credential::key)
                    .filter(key -> !seen.add(key))
                    .distinct()
                    .forEach(duplicate -> errors
                            .add("auth_config.credentials contains duplicate key '%s'".formatted(duplicate)));
        }
        return errors;
    }
}
