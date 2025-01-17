package com.comet.opik.api.error;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JsonInvalidFormatExceptionMapper implements ExceptionMapper<InvalidFormatException> {

    @Override
    public Response toResponse(InvalidFormatException exception) {
        log.info("Deserialization exception: {}", exception.getMessage());

        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(),
                        "Unable to process JSON. " + exception.getMessage()))
                .build();
    }
}
