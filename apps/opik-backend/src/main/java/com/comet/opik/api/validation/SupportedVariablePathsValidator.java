package com.comet.opik.api.validation;

import com.comet.opik.utils.VariablePathUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Map;

public class SupportedVariablePathsValidator
        implements
            ConstraintValidator<SupportedVariablePaths, Map<String, String>> {

    @Override
    public boolean isValid(Map<String, String> variables, ConstraintValidatorContext context) {
        var violation = VariablePathUtils.validate(variables);
        if (violation.isEmpty()) {
            return true;
        }
        // Replace the default message so the response names the offending variable and construct: the
        // mapping is the user's own input and "contains an unsupported path construct" alone would leave
        // them guessing which one.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(violation.get()).addConstraintViolation();
        return false;
    }
}
