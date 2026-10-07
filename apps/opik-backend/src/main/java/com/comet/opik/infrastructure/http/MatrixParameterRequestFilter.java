package com.comet.opik.infrastructure.http;

import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

@Provider
@PreMatching
@Singleton
@Priority(Priorities.AUTHENTICATION - 100)
public class MatrixParameterRequestFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext context) {
        if (hasMatrixParameters(context.getUriInfo().getRequestUri().getRawPath())) {
            throw new NotFoundException();
        }
    }

    static boolean hasMatrixParameters(String rawPath) {
        return rawPath != null && rawPath.indexOf(';') >= 0;
    }
}
