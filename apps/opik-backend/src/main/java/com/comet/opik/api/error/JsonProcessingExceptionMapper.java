package com.comet.opik.api.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class JsonProcessingExceptionMapper implements ExceptionMapper<JsonProcessingException> {

    @Override
    public Response toResponse(JsonProcessingException exception) {
        log.info("Deserialization exception: {}", exception.getMessage());

        String message = buildMessage(exception);

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(), message))
                .build();
    }

    private String buildMessage(JsonProcessingException exception) {
        if (exception instanceof InvalidFormatException invalidFormatException
                && UUID.class.equals(invalidFormatException.getTargetType())) {
            return "Unable to process JSON. Invalid UUID format: '%s'. For threads, use 'thread_model_id' (a UUID), not 'id' (which may be a custom string)."
                    .formatted(invalidFormatException.getValue());
        }
        return "Unable to process JSON. " + exception.getMessage();
    }
}
