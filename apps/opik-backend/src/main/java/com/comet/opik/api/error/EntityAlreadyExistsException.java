package com.comet.opik.api.error;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;

public class EntityAlreadyExistsException extends ClientErrorException {

    public EntityAlreadyExistsException(ErrorMessage response) {
        this((Object) response);
    }

    public EntityAlreadyExistsException(io.dropwizard.jersey.errors.ErrorMessage response) {
        this((Object) response);
    }

    private EntityAlreadyExistsException(Object entity) {
        super(Response.status(Response.Status.CONFLICT).entity(entity).build());
    }
}
