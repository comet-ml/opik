package com.comet.opik.api.validation;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.infrastructure.EncryptionUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

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
        // no validation for custom LLM
        if (providerApiKey.provider() == LlmProvider.CUSTOM_LLM) {
            return true;
        }

        // if the api key is not empty, return valid
        if (providerApiKey.apiKey() != null && isNotBlank(EncryptionUtils.decrypt(providerApiKey.apiKey()))) {
            return true;
        }

        // if the api key is empty, add custom error message
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(constraintAnnotation.message())
                .addPropertyNode("apiKey")
                .addConstraintViolation();

        return false;
    }
}
