package com.comet.opik.api.validation;

import com.comet.opik.utils.ValidationUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class CommitValidator implements ConstraintValidator<CommitValidation, String> {

    @Override
    public boolean isValid(String commit, ConstraintValidatorContext context) {
        return commit == null || Pattern.matches(ValidationUtils.COMMIT_PATTERN, commit);
    }
}
