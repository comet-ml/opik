package com.comet.opik.api.error;

import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InternalServerErrorExceptionMapper implements ExceptionMapper<InternalServerErrorException> {

    @Override
    public Response toResponse(InternalServerErrorException exception) {
        log.error("Internal server error: {}", exception.getMessage(), exception);

        String message = exception.getMessage();

        // If there's a cause exception, include its message for more context
        if (exception.getCause() != null && exception.getCause().getMessage() != null) {
            message = message + ": " + exception.getCause().getMessage();
        }

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorMessage(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), message))
                .build();
    }
}
