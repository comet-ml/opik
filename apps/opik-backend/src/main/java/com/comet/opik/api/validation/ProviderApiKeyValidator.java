package com.comet.opik.api.validation;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.infrastructure.EncryptionUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

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

        if (provider == LlmProvider.CUSTOM_LLM) {
            if (isBlank(providerName)) {
                // For custom providers, provider_name is required and must not be blank
                context.buildConstraintViolationWithTemplate("provider_name is required for custom LLM providers")
                        .addPropertyNode("providerName")
                        .addConstraintViolation();
                return false;
            }

            // If custom provider, no need to validate api key
            return true;
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
}
