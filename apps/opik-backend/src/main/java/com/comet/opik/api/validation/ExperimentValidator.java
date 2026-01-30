package com.comet.opik.api.validation;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

public class ExperimentValidator implements ConstraintValidator<ExperimentValidation, Experiment> {

    @Override
    public boolean isValid(Experiment experiment, ConstraintValidatorContext context) {
        var type = Optional.ofNullable(experiment.type()).orElse(ExperimentType.REGULAR);

        if (type.requiresDataset() && StringUtils.isBlank(experiment.datasetName())) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate("datasetName must not be blank for experiment type " + type)
                    .addPropertyNode("datasetName")
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
