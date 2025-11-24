package com.comet.opik.api.validation;

import com.comet.opik.api.FeedbackDefinition;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import lombok.NonNull;

public class FeedbackValidator implements ConstraintValidator<FeedbackValidation, FeedbackDefinition<?>> {

    private final ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();

    @Override
    public boolean isValid(@NonNull FeedbackDefinition<?> feedback, @NonNull ConstraintValidatorContext context) {

        if (feedback.getDetails() == null) {
            return true;
        }

        switch (feedback) {
            case FeedbackDefinition.NumericalFeedbackDefinition numericalFeedback -> {
                var details = numericalFeedback.getDetails();
                var result = validatorFactory.getValidator().validate(details);

                if (result.isEmpty()) {

                    if (details.getMin().doubleValue() >= details.getMax().doubleValue()) {
                        context.disableDefaultConstraintViolation();

                        addViolation(
                                context,
                                "has to be smaller than details.max",
                                FeedbackDefinition.NumericalFeedbackDefinition.NumericalFeedbackDetail.class,
                                "min");

                        return false;
                    }

                    return true;
                }

                context.disableDefaultConstraintViolation();

                result.forEach(violation -> addViolation(
                        context,
                        violation.getMessage(),
                        FeedbackDefinition.NumericalFeedbackDefinition.NumericalFeedbackDetail.class,
                        violation.getPropertyPath().toString()));

                return false;
            }
            case FeedbackDefinition.CategoricalFeedbackDefinition categoricalFeedback -> {
                var result = validatorFactory.getValidator().validate(categoricalFeedback.getDetails());

                if (result.isEmpty()) {
                    return true;
                }
                context.disableDefaultConstraintViolation();

                result.forEach(violation -> addViolation(
                        context,
                        violation.getMessage(),
                        FeedbackDefinition.CategoricalFeedbackDefinition.CategoricalFeedbackDetail.class,
                        violation.getPropertyPath().toString()));

                return false;
            }
            case FeedbackDefinition.BooleanFeedbackDefinition booleanFeedback -> {
                var result = validatorFactory.getValidator().validate(booleanFeedback.getDetails());

                if (result.isEmpty()) {
                    return true;
                }
                context.disableDefaultConstraintViolation();

                result.forEach(violation -> addViolation(
                        context,
                        violation.getMessage(),
                        FeedbackDefinition.BooleanFeedbackDefinition.BooleanFeedbackDetail.class,
                        violation.getPropertyPath().toString()));

                return false;
            }
        }
    }

    private void addViolation(ConstraintValidatorContext context, String message,
            Class<?> detailClass, String propertyName) {

        context.buildConstraintViolationWithTemplate(message)
                .addContainerElementNode("details",
                        detailClass, 0)
                .addPropertyNode(propertyName)
                .addConstraintViolation();
    }
}
