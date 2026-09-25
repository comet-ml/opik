package com.comet.opik.api.validation;

import com.comet.opik.domain.FieldMappingResolver;
import com.comet.opik.utils.VariablePathUtils;
import com.jayway.jsonpath.JsonPath;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.Optional;

public class SupportedFieldMappingPathsValidator
        implements
            ConstraintValidator<SupportedFieldMappingPaths, Map<String, String>> {

    private static final int MAX_NAME_LENGTH = 150;

    @Override
    public boolean isValid(Map<String, String> fieldMappings, ConstraintValidatorContext context) {
        if (fieldMappings == null) {
            return true;
        }

        var violations = fieldMappings.entrySet().stream()
                .map(entry -> describeViolation(entry.getKey(), entry.getValue()))
                .flatMap(Optional::stream)
                .toList();

        if (violations.isEmpty()) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("unsupported field mappings: %s"
                .formatted(String.join(", ", violations))).addConstraintViolation();
        return false;
    }

    private Optional<String> describeViolation(String name, String path) {
        if (StringUtils.isBlank(name)) {
            return Optional.of("'%s' (name must not be blank)".formatted(name));
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return Optional.of("'%s' (name cannot exceed %d characters)".formatted(name, MAX_NAME_LENGTH));
        }
        if (StringUtils.isBlank(path)) {
            return Optional.of("'%s' (path must not be blank)".formatted(name));
        }
        String jsonPath = FieldMappingResolver.toJsonPath(path);

        var unsupported = VariablePathUtils.findUnsupportedConstructInJsonPath(jsonPath);
        if (unsupported.isPresent()) {
            return Optional.of("'%s' (%s uses '%s')".formatted(name, path, unsupported.get()));
        }

        try {
            JsonPath.compile(jsonPath);
        } catch (Exception exception) {
            return Optional.of("'%s' (%s is not a valid path)".formatted(name, path));
        }
        return Optional.empty();
    }
}
