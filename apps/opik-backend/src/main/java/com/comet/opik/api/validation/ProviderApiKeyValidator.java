package com.comet.opik.api.validation;

import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderAuthConfig;
import com.comet.opik.infrastructure.EncryptionUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;
import java.util.Optional;

import static org.apache.commons.lang3.StringUtils.isBlank;

public class ProviderApiKeyValidator
        implements
            ConstraintValidator<ProviderApiKeyValidation, ProviderApiKey> {

    private volatile ProviderApiKeyValidation constraintAnnotation;

    @Override
    public void initialize(ProviderApiKeyValidation constraintAnnotation) {
        this.constraintAnnotation = constraintAnnotation;
    }

    @Override
    public boolean isValid(ProviderApiKey providerApiKey, ConstraintValidatorContext context) {
        context.disableDefaultConstraintViolation();

        // Validate provider_name requirements
        var provider = providerApiKey.provider();
        var providerName = providerApiKey.providerName();

        if (provider.supportsProviderName()) {
            if (isBlank(providerName)) {
                // For providers that support naming, provider_name is required and must not be blank
                context.buildConstraintViolationWithTemplate(
                        "provider_name is required for custom LLM and Bedrock providers")
                        .addPropertyNode("providerName")
                        .addConstraintViolation();
                return false;
            }

            // If provider supports naming, no need to validate api key
            return isValidAuthConfig(providerApiKey, context);
        }

        if (providerApiKey.authConfig() != null && !provider.supportsDynamicTokenAuth()) {
            context.buildConstraintViolationWithTemplate(
                    "auth_config is only supported for custom LLM, Bedrock, and Ollama providers")
                    .addPropertyNode("authConfig")
                    .addConstraintViolation();
            return false;
        }

        // Validate API key for non-custom providers
        if (providerApiKey.apiKey() == null || isBlank(EncryptionUtils.decrypt(providerApiKey.apiKey()))) {
            context.buildConstraintViolationWithTemplate(constraintAnnotation.message())
                    .addPropertyNode("apiKey")
                    .addConstraintViolation();
            return false;
        }

        return true;
    }

    private boolean isValidAuthConfig(ProviderApiKey providerApiKey, ConstraintValidatorContext context) {
        var authConfig = providerApiKey.authConfig();
        if (authConfig == null) {
            return true;
        }

        boolean valid = true;
        for (String error : ProviderAuthConfigValidator.validationErrors(authConfig)) {
            context.buildConstraintViolationWithTemplate(error)
                    .addPropertyNode("authConfig")
                    .addConstraintViolation();
            valid = false;
        }

        // The sentinel means "keep the stored secret" — meaningless on create, where nothing is stored yet
        boolean hasSentinel = Optional.ofNullable(authConfig.credentials()).orElse(List.of()).stream()
                .anyMatch(credential -> ProviderAuthConfig.SECRET_SENTINEL.equals(credential.value()));
        if (hasSentinel) {
            context.buildConstraintViolationWithTemplate(
                    "auth_config credential values must not be the '%s' sentinel on create"
                            .formatted(ProviderAuthConfig.SECRET_SENTINEL))
                    .addPropertyNode("authConfig")
                    .addConstraintViolation();
            valid = false;
        }

        if (providerApiKey.apiKey() != null && !isBlank(EncryptionUtils.decrypt(providerApiKey.apiKey()))) {
            context.buildConstraintViolationWithTemplate("api_key must not be set when auth_config is set")
                    .addPropertyNode("apiKey")
                    .addConstraintViolation();
            valid = false;
        }

        return valid;
    }
}
