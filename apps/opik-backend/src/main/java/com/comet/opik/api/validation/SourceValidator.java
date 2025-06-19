package com.comet.opik.api.validation;

import com.comet.opik.api.DatasetItem;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class SourceValidator implements ConstraintValidator<SourceValidation, DatasetItem> {

    @Override
    public boolean isValid(DatasetItem item, ConstraintValidatorContext context) {

        if (item.source() == null) {
            addErrorMessage(context, "source must not be null");
            return false;
        }

        return switch (item.source()) {
            case SDK, MANUAL -> {
                if (item.spanId() != null) {
                    addSourceErrorMessage(context,
                            "when it is %s, span_id must be null".formatted(item.source().getValue()));
                    yield false;
                }

                if (item.traceId() != null) {
                    addSourceErrorMessage(context,
                            "when it is %s, trace_id must be null".formatted(item.source().getValue()));
                    yield false;
                }

                yield true;
            }
            case SPAN -> {
                if (item.spanId() == null) {
                    addSourceErrorMessage(context,
                            "when it is %s, span_id must not be null".formatted(item.source().getValue()));
                    yield false;
                }

                if (item.traceId() == null) {
                    addSourceErrorMessage(context,
                            "when it is %s, trace_id must not be null".formatted(item.source().getValue()));
                    yield false;
                }

                yield true;
            }
            case TRACE -> {
                if (item.spanId() != null) {
                    addSourceErrorMessage(context,
                            "when it is %s, span_id must be null".formatted(item.source().getValue()));
                    yield false;
                }

                if (item.traceId() == null) {
                    addSourceErrorMessage(context,
                            "when it is %s, trace_id must not be null".formatted(item.source().getValue()));
                    yield false;
                }

                yield true;
            }
        };
    }

    private static void addErrorMessage(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();

        context.buildConstraintViolationWithTemplate(message)
                .addBeanNode()
                .addConstraintViolation();
    }

    private static void addSourceErrorMessage(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();

        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode("source")
                .addConstraintViolation();
    }
}
