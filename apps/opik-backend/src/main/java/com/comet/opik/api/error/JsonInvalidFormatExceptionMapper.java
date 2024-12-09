package com.comet.opik.api.error;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class JsonInvalidFormatExceptionMapper implements ExceptionMapper<InvalidFormatException> {

    @Override
    public Response toResponse(InvalidFormatException exception) {
        String errorMessage = exception.getMessage();
        log.info("Deserialization exception: {}", exception.getMessage());
        int endIndex = errorMessage.indexOf(": Failed to deserialize");
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorMessage(
                        List.of(endIndex == -1 ? "Unable to process JSON" : errorMessage.substring(0, endIndex))))
                .build();
    }
}
