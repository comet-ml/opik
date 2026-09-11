package com.comet.opik.api.validation;

import com.comet.opik.utils.ValidationUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class HttpUrlValidator implements ConstraintValidator<HttpUrl, String> {

    @Override
    public void initialize(HttpUrl constraintAnnotation) {
        // No initialization needed
    }

    @Override
    public boolean isValid(String url, ConstraintValidatorContext context) {
        if (url == null) {
            return true; // Let @NotBlank handle null validation
        }

        try {
            ValidationUtils.validateHttpUrl(url, "URL");
            return true;
        } catch (IllegalArgumentException e) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(e.getMessage())
                    .addConstraintViolation();
            return false;
        }
    }
}
