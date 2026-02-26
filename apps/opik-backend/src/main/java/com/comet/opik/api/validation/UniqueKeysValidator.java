package com.comet.opik.api.validation;

import com.comet.opik.domain.AgentBlueprint;
import com.comet.opik.domain.AgentConfigValue;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class UniqueKeysValidator implements ConstraintValidator<UniqueKeysValidation, AgentBlueprint> {

    @Override
    public boolean isValid(AgentBlueprint blueprint, ConstraintValidatorContext context) {
        if (blueprint == null || blueprint.values() == null || blueprint.values().isEmpty()) {
            return true;
        }

        long uniqueKeyCount = blueprint.values().stream()
                .map(AgentConfigValue::key)
                .distinct()
                .count();

        if (uniqueKeyCount != blueprint.values().size()) {
            return false;
        }

        return true;
    }
}
