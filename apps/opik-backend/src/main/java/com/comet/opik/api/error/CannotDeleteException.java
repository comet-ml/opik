package com.comet.opik.api.error;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;

public class CannotDeleteException extends ClientErrorException {

    public CannotDeleteException(ErrorMessage response) {
        super(Response.status(Response.Status.CONFLICT).entity(response).build());
    }

    public CannotDeleteException(String message) {
        super(Response.status(Response.Status.CONFLICT).entity(
                new io.dropwizard.jersey.errors.ErrorMessage(Response.Status.CONFLICT.getStatusCode(), message))
                .build());
    }
}
