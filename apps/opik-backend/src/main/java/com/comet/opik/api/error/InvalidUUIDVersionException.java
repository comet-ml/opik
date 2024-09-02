package com.comet.opik.api.error;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;

public class InvalidUUIDVersionException extends BadRequestException {

    public InvalidUUIDVersionException(ErrorMessage errorMessage) {
        super(Response.status(Response.Status.BAD_REQUEST).entity(errorMessage).build());
    }
}
