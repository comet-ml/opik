package com.comet.opik.infrastructure.llm;

import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;

public class LlmProviderUnsupportedException extends ClientErrorException {

    public LlmProviderUnsupportedException(String message) {
        super(
                Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorMessage(Response.Status.CONFLICT.getStatusCode(), message))
                        .build());
    }
}
