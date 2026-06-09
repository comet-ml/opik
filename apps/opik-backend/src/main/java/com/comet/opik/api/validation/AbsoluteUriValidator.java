package com.comet.opik.api.validation;

import com.comet.opik.utils.ValidationUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class AbsoluteUriValidator implements ConstraintValidator<AbsoluteUri, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return ValidationUtils.isAbsoluteUri(value);
    }
}
