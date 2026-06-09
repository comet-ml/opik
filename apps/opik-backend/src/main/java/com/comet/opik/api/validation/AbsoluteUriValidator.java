package com.comet.opik.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.URI;
import java.net.URISyntaxException;

public class AbsoluteUriValidator implements ConstraintValidator<AbsoluteUri, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // Let @NotEmpty handle null/blank validation
        }

        try {
            return new URI(value).isAbsolute();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
