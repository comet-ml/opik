package com.comet.opik.api.error;

import io.dropwizard.jersey.validation.ValidationErrorMessage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class ConstraintViolationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        log.warn("Validation constraint violation: '{}'", exception.getMessage());

        List<String> errors = exception.getConstraintViolations()
                .stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toList());

        ValidationErrorMessage validationErrorMessage = new ValidationErrorMessage(errors);

        return Response.status(422)
                .entity(validationErrorMessage)
                .build();
    }
}
