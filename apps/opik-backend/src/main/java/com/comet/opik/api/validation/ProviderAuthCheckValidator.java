package com.comet.opik.api.validation;

import com.comet.opik.api.ProviderAuthCheck;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Request-only rules of the auth-config test endpoint. What stays in the service is the pair
 * that reads the DB: "the provider has no auth_config to test" and the secret-sentinel merge.
 */
public class ProviderAuthCheckValidator implements ConstraintValidator<ProviderAuthCheckValidation, ProviderAuthCheck> {

    @Override
    public boolean isValid(ProviderAuthCheck request, ConstraintValidatorContext context) {
        context.disableDefaultConstraintViolation();

        var authConfig = request.authConfig();
        if ((authConfig == null || authConfig.isEmpty()) && request.providerId() == null) {
            context.buildConstraintViolationWithTemplate("either provider_id or auth_config must be provided")
                    .addConstraintViolation();
            return false;
        }

        if (authConfig != null && !authConfig.isEmpty()) {
            var errors = ProviderAuthConfigValidator.validationErrors(authConfig);
            if (!errors.isEmpty()) {
                errors.forEach(error -> context.buildConstraintViolationWithTemplate(error)
                        .addPropertyNode("authConfig")
                        .addConstraintViolation());
                return false;
            }
        }
        return true;
    }
}
