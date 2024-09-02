package com.comet.opik.api.error;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;

public class IdentifierMismatchException extends ClientErrorException {
    public IdentifierMismatchException(ErrorMessage message) {
        super(Response.status(Response.Status.CONFLICT).entity(message).build());
    }
}
