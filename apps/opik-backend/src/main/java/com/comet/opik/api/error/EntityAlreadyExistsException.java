package com.comet.opik.api.error;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;

public class EntityAlreadyExistsException extends ClientErrorException {

    public EntityAlreadyExistsException(ErrorMessage response) {
        super(Response.status(Response.Status.CONFLICT).entity(response).build());
    }
}
